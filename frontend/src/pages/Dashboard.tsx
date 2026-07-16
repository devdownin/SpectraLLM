import type { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LineChart, Line, XAxis, YAxis, Tooltip as RechartsTooltip, ResponsiveContainer, CartesianGrid, ReferenceLine } from 'recharts';
import { useTranslation } from 'react-i18next';
import { useStatus } from '../hooks/useStatus';
import { datasetApi, gedApi, metricsApi } from '../services/api';
import Skeleton from '../components/Skeleton';
import Tooltip from '../components/Tooltip';
import LifecycleDonut from '../components/charts/LifecycleDonut';
import CategoryBar from '../components/charts/CategoryBar';
import EmbeddingConsistencyCard from '../components/EmbeddingConsistencyCard';

interface DatasetStats {
  totalPairs: number;
  chunksInStore: number;
  avgConfidence: number;
  byCategory: Record<string, number>;
}

interface GedStats {
  totalDocuments?: number;
  byLifecycle?: Record<string, number>;
  avgQualityScore?: number;
  totalChunks?: number;
}

interface CommentStats {
  total: number;
  approved: number;
  rejected: number;
  aiGenerated: number;
}

interface FtJob {
  status: string;
  modelName: string;
  loss?: number;
  completedAt?: string;
  createdAt?: string;
}

interface EvalResult {
  status: string;
  averageScore: number;
  completedAt?: string;
  modelName?: string;
}

interface PersonalizationMetrics {
  approvedComments: number;
  rejectedComments: number;
  totalAiComments: number;
  dpoPairs: number;
  fineTuningJobs: FtJob[];
  evaluations: EvalResult[];
  completedCycles: number;
  nextTriggerIn: number;
  autoRetrainThreshold: number;
  completedFineTuningJobs: number;
  latestEvalScore: number;
}

function relativeTime(iso?: string): string {
  if (!iso) return '—';
  const delta = (Date.now() - new Date(iso).getTime()) / 1000;
  if (delta < 60)   return `${Math.round(delta)}s`;
  if (delta < 3600) return `${Math.round(delta / 60)}m`;
  if (delta < 86400) return `${Math.round(delta / 3600)}h`;
  return `${Math.round(delta / 86400)}d`;
}

const Dashboard: FC = () => {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();
  const { status, loading } = useStatus();

  const statusChip = (status: string): { label: string; cls: string } => {
    switch (status.toUpperCase()) {
      case 'COMPLETED': return { label: t('dashboard.statusOk'),      cls: 'text-primary border-primary/40 bg-primary/5' };
      case 'TRAINING':
      case 'PROCESSING':return { label: t('dashboard.statusRunning'), cls: 'text-secondary border-secondary/40 bg-secondary/5' };
      case 'FAILED':    return { label: t('dashboard.statusFailed'),  cls: 'text-error border-error/40 bg-error/5' };
      default:          return { label: status.slice(0, 6), cls: 'text-outline border-outline-variant/30' };
    }
  };
  // Stats périodiques (dataset + GED + métriques) via React Query — Promise.allSettled
  // pour que l'échec d'une source n'invalide pas les autres ; polling 30 s.
  const { data: statsData, isLoading: statsLoading } = useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: async () => {
      const [dsRes, gedRes, metricsRes] = await Promise.allSettled([
        datasetApi.getStats(),
        gedApi.getStats(),
        metricsApi.getPersonalization(),
      ]);
      const errors: string[] = [];
      const out = {
        stats: null as DatasetStats | null,
        gedStats: null as GedStats | null,
        commentStatsFromGed: null as CommentStats | null,
        personalizationMetrics: null as PersonalizationMetrics | null,
        errors,
      };
      if (dsRes.status === 'fulfilled') out.stats = dsRes.value.data; else errors.push('dataset');
      if (gedRes.status === 'fulfilled') {
        out.gedStats = gedRes.value.data;
        if (gedRes.value.data.commentStats) out.commentStatsFromGed = gedRes.value.data.commentStats;
      } else {
        errors.push('ged');
      }
      if (metricsRes.status === 'fulfilled') out.personalizationMetrics = metricsRes.value.data; else errors.push('metrics');
      return out;
    },
    refetchInterval: 30_000,
  });

  const stats = statsData?.stats ?? null;
  const gedStats = statsData?.gedStats ?? null;
  const personalizationMetrics = statsData?.personalizationMetrics ?? null;
  const statsErrors = statsData?.errors ?? [];
  // Stats de commentaires : agrégat serveur embarqué dans /api/ged/stats. Remplace
  // l'ancien calcul côté client (1 listDocuments + 20 GET de commentaires toutes les
  // 60 s, tronqué aux 20 premiers documents → chiffres faux dès le 21e document).
  const commentStats: CommentStats | null = statsData?.commentStatsFromGed ?? null;
  // Les pannes réseau / 5xx sont signalées globalement par l'intercepteur axios.

  const chatSvc  = status?.services?.find((s: { name: string }) => s.name === 'llama-cpp');
  const embedSvc = status?.services?.find((s: { name: string }) => s.name === 'llama-cpp-embed');
  const chromadb = status?.services?.find((s: { name: string }) => s.name === 'chromadb');

  const pipelineReady = (stats?.chunksInStore ?? 0) > 0 && (stats?.totalPairs ?? 0) > 0;
  const totalDocs = gedStats?.byLifecycle
    ? Object.values(gedStats.byLifecycle).reduce((a: number, b: unknown) => a + (b as number), 0)
    : 0;
  const dpoPairsReady = (commentStats?.approved ?? 0) > 0;

  return (
    <div className="space-y-12 animate-in fade-in duration-700">

      {/* Header */}
      <header>
        <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">{t('dashboard.kicker')}</p>
        <h2 className="font-headline text-3xl font-bold tracking-tighter">{t('dashboard.title')}</h2>
      </header>

      {/* ── Cohérence embedding ↔ index (visible seulement en cas de problème) ── */}
      <EmbeddingConsistencyCard />

      {/* ── Service Health ── */}
      <section className="space-y-4">
        <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">{t('dashboard.serviceHealth')}</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">

          <div className="bg-surface-container p-6 flex items-center justify-between card-hover">
            <div className="flex items-center gap-4">
              <div className={`w-10 h-10 flex items-center justify-center border ${
                loading ? 'border-outline-variant/30 text-outline' :
                chatSvc?.available ? 'border-primary bg-primary/10 text-primary' :
                'border-error bg-error/10 text-error'
              }`}>
                <span className="material-symbols-outlined text-base">memory</span>
              </div>
              <div>
                <p className="font-headline font-bold text-sm uppercase">Chat</p>
                <p className="text-[11px] text-on-surface-variant uppercase tracking-widest">LLM Inference · llama.cpp</p>
              </div>
            </div>
            <div className="text-right">
              {loading ? <Skeleton className="h-4 w-16" /> : (
                <>
                  <p className={`text-[11px] font-bold uppercase tracking-widest ${chatSvc?.available ? 'text-primary' : 'text-error'}`}>
                    {chatSvc?.available ? t('dashboard.online') : t('dashboard.offline')}
                  </p>
                  {chatSvc?.details?.activeModel && (
                    <p className="text-[10px] text-outline font-mono mt-0.5 max-w-[120px] truncate" title={chatSvc.details.activeModel}>
                      {chatSvc.details.activeModel}
                    </p>
                  )}
                  {chatSvc?.available && chatSvc?.details?.activeModelLoaded === false && (
                    <p className="text-[10px] font-bold text-error uppercase tracking-widest mt-1 flex items-center gap-1">
                      <span className="material-symbols-outlined text-[11px]">warning</span>
                      {t('dashboard.modelNotLoaded')}
                    </p>
                  )}
                </>
              )}
            </div>
          </div>

          <div className="bg-surface-container p-6 flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className={`w-10 h-10 flex items-center justify-center border ${
                loading ? 'border-outline-variant/30 text-outline' :
                embedSvc?.available ? 'border-secondary bg-secondary/10 text-secondary' :
                !embedSvc ? 'border-outline-variant/30 text-outline' :
                'border-error bg-error/10 text-error'
              }`}>
                <span className="material-symbols-outlined text-base">hub</span>
              </div>
              <div>
                <p className="font-headline font-bold text-sm uppercase">Embed</p>
                <p className="text-[11px] text-on-surface-variant uppercase tracking-widest">Embeddings · llama.cpp</p>
              </div>
            </div>
            <div className="text-right">
              {loading ? <Skeleton className="h-4 w-16" /> : !embedSvc ? (
                <p className="text-[11px] font-bold uppercase tracking-widest text-outline">N/A</p>
              ) : (
                <>
                  <p className={`text-[11px] font-bold uppercase tracking-widest ${embedSvc.available ? 'text-secondary' : 'text-error'}`}>
                    {embedSvc.available ? t('dashboard.online') : t('dashboard.offline')}
                  </p>
                  {embedSvc?.details?.activeModel && (
                    <p className="text-[10px] text-outline font-mono mt-0.5 max-w-[120px] truncate" title={embedSvc.details.activeModel}>
                      {embedSvc.details.activeModel}
                    </p>
                  )}
                </>
              )}
            </div>
          </div>

          <div className="bg-surface-container p-6 flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className={`w-10 h-10 flex items-center justify-center border ${
                loading ? 'border-outline-variant/30 text-outline' :
                chromadb?.available ? 'border-primary bg-primary/10 text-primary' :
                'border-error bg-error/10 text-error'
              }`}>
                <span className="material-symbols-outlined text-base">database</span>
              </div>
              <div>
                <p className="font-headline font-bold text-sm uppercase">ChromaDB</p>
                <p className="text-[11px] text-on-surface-variant uppercase tracking-widest">Vector Storage · API v2</p>
              </div>
            </div>
            <div className="text-right">
              {loading ? <Skeleton className="h-4 w-16" /> : (
                <>
                  <p className={`text-[11px] font-bold uppercase tracking-widest ${chromadb?.available ? 'text-primary' : 'text-error'}`}>
                    {chromadb?.available ? t('dashboard.online') : t('dashboard.offline')}
                  </p>
                  {!statsLoading && (stats?.chunksInStore ?? 0) > 0 && (
                    <p className="text-[10px] text-outline font-mono mt-0.5">{stats!.chunksInStore} chunks</p>
                  )}
                </>
              )}
            </div>
          </div>

        </div>
      </section>

      {/* ── Getting Started (shown only when no data yet) ── */}
      {!statsLoading && !loading && (stats?.chunksInStore ?? 0) === 0 && (
        <section className="space-y-4">
          <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">{t('dashboard.gettingStarted')}</h3>

          <div className="bg-surface-container p-5 space-y-3">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-3">{t('dashboard.prerequisites')}</p>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {[
                { ok: chatSvc?.available ?? false, label: t('dashboard.chatModel'), hint: t('dashboard.chatModelHint'), icon: 'memory' },
                { ok: embedSvc?.available ?? false, label: t('dashboard.embeddingModel'), hint: t('dashboard.embeddingModelHint'), icon: 'hub' },
                { ok: chromadb?.available ?? false, label: 'ChromaDB', hint: t('dashboard.chromaHint'), icon: 'database' },
              ].map(item => (
                <div key={item.label} className={`flex items-start gap-3 p-3 border ${item.ok ? 'border-primary/20 bg-primary/5' : 'border-error/20 bg-error/5'}`}>
                  <span className={`material-symbols-outlined text-sm mt-0.5 ${item.ok ? 'text-primary' : 'text-error'}`}>
                    {item.ok ? 'check_circle' : 'cancel'}
                  </span>
                  <div className="min-w-0">
                    <p className="font-label text-[11px] font-bold uppercase tracking-widest">{item.label}</p>
                    {!item.ok && <p className="text-[10px] text-on-surface-variant mt-0.5 leading-relaxed">{item.hint}</p>}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {[
              { num: '1', title: t('dashboard.step1Title'), desc: t('dashboard.step1Desc'), action: t('dashboard.step1Action'), route: '/ingestion', icon: 'cloud_upload' },
              { num: '2', title: t('dashboard.step2Title'), desc: t('dashboard.step2Desc'), action: t('dashboard.step2Action'), route: '/ingestion', icon: 'dataset' },
              { num: '3', title: t('dashboard.step3Title'), desc: t('dashboard.step3Desc'), action: t('dashboard.step3Action'), route: '/fine-tuning', icon: 'model_training' },
            ].map(step => (
              <div key={step.num} className="bg-surface-container p-5 space-y-3">
                <div className="flex items-center gap-3">
                  <div className="w-7 h-7 bg-primary/10 flex items-center justify-center shrink-0">
                    <span className="font-headline font-bold text-xs text-primary">{step.num}</span>
                  </div>
                  <p className="font-headline font-bold text-sm uppercase">{step.title}</p>
                </div>
                <p className="text-[11px] text-on-surface-variant leading-relaxed">{step.desc}</p>
                <button onClick={() => navigate(step.route)} className="w-full text-left text-[10px] font-label font-bold uppercase tracking-widest text-primary hover:text-primary/70 transition-colors flex items-center gap-1">
                  <span className="material-symbols-outlined text-[11px]">arrow_forward</span>
                  {step.action}
                </button>
              </div>
            ))}
          </div>

          <div className="p-4 border border-outline-variant/20 bg-surface-container">
            <p className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant flex items-center gap-2">
              <span className="material-symbols-outlined text-sm text-outline">tip</span>
              {t('dashboard.tipPrefix')} <code className="font-mono bg-surface-container-high px-1">examples/</code> {t('dashboard.tipFolder')}{' '}
              <button onClick={() => navigate('/ingestion')} className="text-primary hover:text-primary/70 underline underline-offset-2 uppercase">{t('dashboard.tipIngestionPage')}</button>
              {' '}(Windows : <code className="font-mono bg-surface-container-high px-1">adddoc.bat examples</code>) {t('dashboard.tipSuffix')}
            </p>
          </div>
        </section>
      )}

      {/* ── Knowledge Base Stats ── */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">{t('dashboard.knowledgeBase')}</h3>
            {statsErrors.includes('dataset') && (
              <Tooltip content={t('dashboard.datasetStatsError')}>
                <span className="material-symbols-outlined text-sm text-error cursor-help">warning</span>
              </Tooltip>
            )}
          </div>
          <Tooltip content={t('dashboard.kbTooltip')}>
            <span className="material-symbols-outlined text-sm text-outline cursor-help">info</span>
          </Tooltip>
        </div>
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">

          <div className="bg-surface-container p-5 border-t-2 border-primary card-hover">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('dashboard.chunksInStore')}</p>
            {statsLoading ? <Skeleton className="h-9 w-16" /> : (
              <p className="font-headline font-bold text-3xl text-primary stat-glow">{stats?.chunksInStore ?? 0}</p>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-secondary card-hover">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('dashboard.trainingPairs')}</p>
            {statsLoading ? <Skeleton className="h-9 w-16" /> : (
              <p className="font-headline font-bold text-3xl text-secondary stat-glow-secondary">{stats?.totalPairs ?? 0}</p>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-outline-variant card-hover">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('dashboard.avgConfidence')}</p>
            {statsLoading ? <Skeleton className="h-9 w-16" /> : (
              <p className="font-headline font-bold text-3xl">
                {stats && stats.avgConfidence > 0 ? (stats.avgConfidence * 100).toFixed(0) + '%' : '—'}
              </p>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-outline-variant card-hover">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('dashboard.categories')}</p>
            {statsLoading ? <Skeleton className="h-9 w-16" /> : (
              <p className="font-headline font-bold text-3xl">
                {stats ? Object.keys(stats.byCategory).length : 0}
              </p>
            )}
          </div>

        </div>

        {/* Pipeline status banner */}
        {!statsLoading && (
          <div className={`p-4 border flex items-center gap-3 ${pipelineReady ? 'border-primary/30 bg-primary/5' : 'border-outline-variant/20 bg-surface-container'}`}>
            <span className={`material-symbols-outlined text-sm ${pipelineReady ? 'text-primary' : 'text-outline'}`}>
              {pipelineReady ? 'check_circle' : 'radio_button_unchecked'}
            </span>
            <p className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant">
              {pipelineReady
                ? t('dashboard.pipelineReady', { chunks: stats!.chunksInStore, pairs: stats!.totalPairs })
                : (stats?.chunksInStore ?? 0) > 0
                  ? t('dashboard.pipelineChunksOnly')
                  : t('dashboard.pipelineEmpty')}
            </p>
          </div>
        )}
      </section>

      {/* ── GED + Comment Stats ── */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">{t('dashboard.docsAnnotations')}</h3>
            {statsErrors.includes('ged') && (
              <Tooltip content={t('dashboard.gedStatsError')}>
                <span className="material-symbols-outlined text-sm text-error cursor-help">warning</span>
              </Tooltip>
            )}
          </div>
          <button
            onClick={() => navigate('/documents')}
            className="text-[10px] font-label font-bold uppercase tracking-widest text-primary hover:text-primary/70 transition-colors flex items-center gap-1"
          >
            <span className="material-symbols-outlined text-[11px]">arrow_forward</span>
            {t('dashboard.manageDocs')}
          </button>
        </div>

        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">

          <div className="bg-surface-container p-5 border-t-2 border-primary/60">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('dashboard.docsCard')}</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl">{totalDocs}</p>
                {gedStats?.byLifecycle && (
                  <div className="flex gap-1 mt-2 flex-wrap">
                    {Object.entries(gedStats.byLifecycle).map(([lc, n]) => (
                      <span key={lc} className="text-[10px] font-bold uppercase px-1 py-0.5 border border-outline-variant/20 text-outline">
                        {lc.slice(0, 3)} {String(n)}
                      </span>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-secondary/60">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('dashboard.aiComments')}</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl">{commentStats?.aiGenerated ?? 0}</p>
                <p className="text-[10px] text-on-surface-variant mt-1">
                  {t('dashboard.totalComments', { count: commentStats?.total ?? 0 })}
                </p>
              </>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-primary/40">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('dashboard.reviewed')}</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl">
                  {(commentStats?.approved ?? 0) + (commentStats?.rejected ?? 0)}
                </p>
                <div className="flex gap-3 mt-2">
                  <span className="flex items-center gap-1 text-[10px] font-bold text-primary" title={t('dashboard.approved')}>
                    <span aria-hidden="true" className="material-symbols-outlined text-[12px]">thumb_up</span>
                    {commentStats?.approved ?? 0}
                  </span>
                  <span className="flex items-center gap-1 text-[10px] font-bold text-error" title={t('dashboard.rejected')}>
                    <span aria-hidden="true" className="material-symbols-outlined text-[12px]">thumb_down</span>
                    {commentStats?.rejected ?? 0}
                  </span>
                </div>
              </>
            )}
          </div>

          <div className={`bg-surface-container p-5 border-t-2 ${dpoPairsReady ? 'border-primary' : 'border-outline-variant/30'}`}>
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('dashboard.dpoReady')}</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className={`font-headline font-bold text-3xl ${dpoPairsReady ? 'text-primary' : 'text-outline'}`}>
                  {dpoPairsReady ? '✓' : '—'}
                </p>
                <p className="text-[10px] text-on-surface-variant mt-1">
                  {dpoPairsReady ? t('dashboard.exportablePairs', { count: commentStats!.approved }) : t('dashboard.noApproved')}
                </p>
              </>
            )}
          </div>

        </div>

        {/* Comment DPO hint */}
        {!statsLoading && (commentStats?.aiGenerated ?? 0) > 0 && (commentStats?.approved ?? 0) === 0 && (
          <div className="p-4 border border-secondary/20 bg-secondary/5 flex items-start gap-3">
            <span className="material-symbols-outlined text-sm text-secondary mt-0.5 shrink-0">rate_review</span>
            <p className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant">
              {t('dashboard.reviewHint', { count: commentStats!.aiGenerated })}
            </p>
          </div>
        )}
        {!statsLoading && dpoPairsReady && (
          <div className="p-4 border border-primary/30 bg-primary/5 flex items-start gap-3">
            <span className="material-symbols-outlined text-sm text-primary mt-0.5 shrink-0">check_circle</span>
            <p className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant">
              {t('dashboard.dpoHint', { count: commentStats!.approved })}
            </p>
          </div>
        )}
      </section>

      {/* ── Data Visualizations ── */}
      {!statsLoading && (gedStats?.byLifecycle || stats?.byCategory) && (
        <section className="space-y-4">
          <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">{t('dashboard.visualizations')}</h3>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

            {gedStats?.byLifecycle && Object.keys(gedStats.byLifecycle).length > 0 && (
              <div className="bg-surface-container p-5">
                <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-3">
                  {t('dashboard.docsByLifecycle')}
                </p>
                <div className="flex items-center gap-4">
                  <div className="h-36 w-36 shrink-0 relative">
                    <LifecycleDonut byLifecycle={gedStats.byLifecycle} />
                  </div>
                  <div className="space-y-1.5">
                    {Object.entries(gedStats.byLifecycle).filter(([, n]) => n > 0).map(([lc, n]) => (
                      <div key={lc} className="flex items-center gap-2">
                        {/* Doit correspondre aux couleurs de LifecycleDonut / aux valeurs de Lifecycle. */}
                        <div className={`w-2 h-2 shrink-0 ${
                          lc === 'INGESTED'  ? 'bg-[#8ff5ff]' :
                          lc === 'QUALIFIED' ? 'bg-[#b8b3ff]' :
                          lc === 'TRAINED'   ? 'bg-[#4cffb3]' :
                          lc === 'ARCHIVED'  ? 'bg-[#5a6a8a]' :
                                              'bg-[#ff6b8a]'
                        }`} />
                        <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{lc}</span>
                        <span className="font-headline font-bold text-xs ml-auto">{String(n)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {stats?.byCategory && Object.keys(stats.byCategory).length > 0 && (
              <div className="bg-surface-container p-5">
                <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-3">
                  {t('dashboard.pairsByCategory')}
                </p>
                <div className="h-36">
                  <CategoryBar byCategory={stats.byCategory} />
                </div>
              </div>
            )}

          </div>
        </section>
      )}

      {/* ── Personalization Cycle ── */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">{t('dashboard.personalization')}</h3>
            {statsErrors.includes('metrics') && (
              <Tooltip content={t('dashboard.metricsError')}>
                <span className="material-symbols-outlined text-sm text-error cursor-help">warning</span>
              </Tooltip>
            )}
          </div>
          <Tooltip content={t('dashboard.personalizationTooltip')}>
            <span className="material-symbols-outlined text-sm text-outline cursor-help">info</span>
          </Tooltip>
        </div>

        {/* KPI row */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">

          {/* Approuvés + approval ratio */}
          <div className="bg-surface-container p-5 border-t-2 border-primary space-y-2">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{t('dashboard.approved')}</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl text-primary">
                  {personalizationMetrics?.approvedComments ?? 0}
                </p>
                {/* approval/rejection mini-bar */}
                {(() => {
                  const m = personalizationMetrics;
                  if (!m || m.totalAiComments === 0) return (
                    <p className="text-[10px] text-on-surface-variant">{t('dashboard.noAiComments')}</p>
                  );
                  const pending = m.totalAiComments - m.approvedComments - m.rejectedComments;
                  const pctA = (m.approvedComments / m.totalAiComments) * 100;
                  const pctR = (m.rejectedComments / m.totalAiComments) * 100;
                  const pctP = (pending / m.totalAiComments) * 100;
                  return (
                    <div className="space-y-1">
                      <div className="flex h-1.5 w-full overflow-hidden rounded-sm">
                        <div className="bg-primary transition-all" style={{ width: `${pctA}%` }} />
                        <div className="bg-error transition-all"   style={{ width: `${pctR}%` }} />
                        <div className="bg-outline-variant/30 transition-all" style={{ width: `${pctP}%` }} />
                      </div>
                      <div className="flex gap-3 text-[10px] text-outline">
                        <span className="flex items-center gap-1 text-primary" title={t('dashboard.approved')}>
                          <span aria-hidden="true" className="material-symbols-outlined text-[12px]">thumb_up</span>{m.approvedComments}
                        </span>
                        <span className="flex items-center gap-1 text-error" title={t('dashboard.rejected')}>
                          <span aria-hidden="true" className="material-symbols-outlined text-[12px]">thumb_down</span>{m.rejectedComments}
                        </span>
                        <span className="flex items-center gap-1" title={t('dashboard.pendingTitle')}>
                          <span aria-hidden="true" className="material-symbols-outlined text-[12px]">schedule</span>{pending}
                        </span>
                      </div>
                    </div>
                  );
                })()}
              </>
            )}
          </div>

          {/* Paires DPO */}
          <div className="bg-surface-container p-5 border-t-2 border-secondary space-y-2">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{t('dashboard.dpoPairs')}</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl text-secondary">
                  {personalizationMetrics?.dpoPairs ?? 0}
                </p>
                <p className="text-[10px] text-on-surface-variant">
                  {(personalizationMetrics?.dpoPairs ?? 0) > 0
                    ? t('dashboard.dpoGuard')
                    : t('dashboard.noDpoPairs')}
                </p>
              </>
            )}
          </div>

          {/* Fine-Tunings */}
          <div className="bg-surface-container p-5 border-t-2 border-outline-variant space-y-2">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{t('dashboard.fineTunings')}</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl">
                  {personalizationMetrics?.completedFineTuningJobs ?? 0}
                </p>
                <p className="text-[10px] text-on-surface-variant">
                  {t('dashboard.completedTotal', { count: (personalizationMetrics?.fineTuningJobs ?? []).length })}
                </p>
              </>
            )}
          </div>

          {/* Score Éval. avec tendance */}
          <div className="bg-surface-container p-5 border-t-2 border-outline-variant space-y-2">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{t('dashboard.evalScore')}</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (() => {
              const m = personalizationMetrics;
              const completed = (m?.evaluations ?? []).filter(e => e.status === 'COMPLETED');
              const last  = completed.at(-1);
              const prev  = completed.at(-2);
              const delta = last && prev ? last.averageScore - prev.averageScore : null;
              return (
                <>
                  <div className="flex items-end gap-2">
                    <p className="font-headline font-bold text-3xl">
                      {last ? last.averageScore.toFixed(1) : '—'}
                    </p>
                    {delta !== null && (
                      <span className={`text-[11px] font-bold mb-1 ${delta >= 0 ? 'text-primary' : 'text-error'}`}>
                        {delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toFixed(1)}
                      </span>
                    )}
                  </div>
                  <p className="text-[10px] text-on-surface-variant">
                    {last ? t('dashboard.evalMeta', { time: relativeTime(last.completedAt) }) : t('dashboard.noEval')}
                  </p>
                </>
              );
            })()}
          </div>

        </div>

        {/* Auto-trigger progress */}
        {!statsLoading && personalizationMetrics && personalizationMetrics.autoRetrainThreshold > 0 && (
          <div className="bg-surface-container p-4 space-y-2">
            <div className="flex items-center justify-between">
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
                {t('dashboard.nextRetrain')}
              </p>
              <p className="text-[10px] font-mono text-outline">
                {t('dashboard.threshold', { count: personalizationMetrics.autoRetrainThreshold })}
              </p>
            </div>
            <div className="w-full bg-surface-container-high h-1.5">
              <div
                className="h-1.5 bg-primary transition-all duration-500"
                style={{
                  width: `${Math.min(100, ((personalizationMetrics.autoRetrainThreshold - personalizationMetrics.nextTriggerIn) / personalizationMetrics.autoRetrainThreshold) * 100)}%`
                }}
              />
            </div>
            <div className="flex justify-between">
              <p className="text-[10px] text-outline">
                {personalizationMetrics.autoRetrainThreshold - personalizationMetrics.nextTriggerIn} / {personalizationMetrics.autoRetrainThreshold}
              </p>
              <p className={`text-[10px] font-bold ${personalizationMetrics.nextTriggerIn <= 1 ? 'text-primary' : 'text-outline'}`}>
                {personalizationMetrics.nextTriggerIn > 0
                  ? t('dashboard.moreApprovals', { count: personalizationMetrics.nextTriggerIn })
                  : t('dashboard.triggerImminent')}
              </p>
            </div>
            {personalizationMetrics.completedCycles > 0 && (
              <p className="text-[10px] text-primary font-label uppercase tracking-widest flex items-center gap-1">
                <span className="material-symbols-outlined text-[11px]">check_circle</span>
                {t('dashboard.cyclesCompleted', { count: personalizationMetrics.completedCycles })}
              </p>
            )}
          </div>
        )}

        {/* Recent fine-tuning jobs + recent evaluations side by side */}
        {!statsLoading && personalizationMetrics && (
          (personalizationMetrics.fineTuningJobs.length > 0 || personalizationMetrics.evaluations.length > 0)
        ) && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

            {/* Recent jobs */}
            {(personalizationMetrics?.fineTuningJobs.length ?? 0) > 0 && (
              <div className="bg-surface-container p-4 space-y-3">
                <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
                  {t('dashboard.recentJobs')}
                </p>
                <div className="space-y-2">
                  {(personalizationMetrics!.fineTuningJobs)
                    .slice()
                    .reverse()
                    .slice(0, 4)
                    .map((job, i) => {
                      const chip = statusChip(job.status);
                      return (
                        <div key={i} className="flex items-center justify-between gap-2 py-1.5 border-b border-outline-variant/10 last:border-0">
                          <div className="flex items-center gap-2 min-w-0">
                            <span className="material-symbols-outlined text-[11px] text-outline shrink-0">model_training</span>
                            <span className="font-mono text-[10px] text-on-surface-variant truncate" title={job.modelName}>
                              {job.modelName}
                            </span>
                          </div>
                          <div className="flex items-center gap-2 shrink-0">
                            {job.loss != null && (
                              <span className="text-[10px] text-outline font-mono">{t('dashboard.lossLabel', { value: job.loss.toFixed(3) })}</span>
                            )}
                            <span className={`text-[10px] font-bold uppercase px-1.5 py-0.5 border ${chip.cls}`}>
                              {chip.label}
                            </span>
                            <span className="text-[10px] text-outline w-6 text-right">
                              {relativeTime(job.completedAt ?? job.createdAt)}
                            </span>
                          </div>
                        </div>
                      );
                    })}
                </div>
                <button
                  onClick={() => navigate('/fine-tuning')}
                  className="text-[10px] font-label font-bold uppercase tracking-widest text-primary hover:text-primary/70 transition-colors flex items-center gap-1"
                >
                  <span className="material-symbols-outlined text-[11px]">arrow_forward</span>
                  {t('dashboard.viewAllJobs')}
                </button>
              </div>
            )}

            {/* Evaluations trend chart */}
            {(personalizationMetrics?.evaluations.filter(e => e.status === 'COMPLETED').length ?? 0) > 0 && (
              <div className="bg-surface-container p-4 space-y-3 flex flex-col h-full">
                <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant flex items-center justify-between">
                  <span>{t('dashboard.scoreEvolution')}</span>
                  <button type="button" onClick={() => navigate('/comparison')} className="hover:text-primary transition-colors flex items-center gap-1">
                     <span className="material-symbols-outlined text-[11px]">open_in_new</span> {t('dashboard.details')}
                  </button>
                </p>
                <div className="flex-1 w-full mt-2 min-h-[140px]">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart
                      data={personalizationMetrics!.evaluations
                        .filter(e => e.status === 'COMPLETED')
                        // Keep oldest to newest for the chart (left to right)
                        .map((ev, i) => ({
                          name: t('dashboard.evalLabel', { index: i + 1 }),
                          score: Number(ev.averageScore.toFixed(2)),
                          date: ev.completedAt ? new Date(ev.completedAt).toLocaleDateString(i18n.language) : 'N/A',
                        }))}
                      margin={{ top: 15, right: 10, left: -25, bottom: 0 }}
                    >
                      <CartesianGrid strokeDasharray="3 3" stroke="var(--color-outline-variant)" opacity={0.2} vertical={false} />
                      <XAxis dataKey="name" tick={{ fontSize: 10, fill: 'var(--color-on-surface-variant)' }} axisLine={false} tickLine={false} />
                      <YAxis domain={[0, 10]} tick={{ fontSize: 10, fill: 'var(--color-on-surface-variant)' }} axisLine={false} tickLine={false} tickCount={6} />
                      <RechartsTooltip
                        contentStyle={{ backgroundColor: 'var(--color-surface-container-high)', border: '1px solid var(--color-outline-variant)', borderRadius: '4px', fontSize: '11px', color: 'var(--color-on-surface)' }}
                        itemStyle={{ color: 'var(--color-primary)' }}
                        labelStyle={{ color: 'var(--color-on-surface-variant)', marginBottom: '4px' }}
                      />
                      <ReferenceLine y={7} stroke="var(--color-secondary)" strokeDasharray="3 3" opacity={0.3} label={{ position: 'insideTopLeft', value: t('dashboard.goodRef'), fill: 'var(--color-secondary)', fontSize: 10, opacity: 0.5 }} />
                      <Line type="monotone" dataKey="score" stroke="var(--color-primary)" strokeWidth={2} dot={{ r: 3, fill: 'var(--color-primary)' }} activeDot={{ r: 5, fill: 'var(--color-primary)', stroke: 'var(--color-surface)', strokeWidth: 2 }} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>
            )}

          </div>
        )}

        {/* Empty state — no metrics at all */}
        {!statsLoading && (!personalizationMetrics || (
          personalizationMetrics.totalAiComments === 0 &&
          personalizationMetrics.fineTuningJobs.length === 0
        )) && (
          <div className="p-4 border border-outline-variant/20 bg-surface-container flex items-start gap-3">
            <span className="material-symbols-outlined text-sm text-outline mt-0.5 shrink-0">info</span>
            <p className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant leading-relaxed">
              {t('dashboard.noPersonalization')}
            </p>
          </div>
        )}
      </section>

      {/* ── RAG Capabilities ── */}
      <section className="space-y-4">
        <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">{t('dashboard.ragCapabilities')}</h3>
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-2">
          {[
            { key: 'hybrid',     icon: 'merge',        color: 'primary' },
            { key: 'rerank',     icon: 'sort',         color: 'secondary' },
            { key: 'multiQuery', icon: 'dynamic_feed', color: 'primary' },
            { key: 'agentic',    icon: 'psychology',   color: 'secondary' },
            { key: 'corrective', icon: 'fact_check',   color: 'primary' },
            { key: 'comments',   icon: 'rate_review',  color: 'secondary' },
          ].map(cap => (
            <div key={cap.key} className={`bg-surface-container p-4 border border-outline-variant/10 hover:border-${cap.color}/30 transition-colors group`}>
              <span className={`material-symbols-outlined text-base text-outline group-hover:text-${cap.color} transition-colors`}>{cap.icon}</span>
              <p className="font-headline font-bold text-[11px] uppercase mt-2">{t(`dashboard.caps.${cap.key}.label`)}</p>
              <p className="text-[10px] text-on-surface-variant mt-1 leading-relaxed">{t(`dashboard.caps.${cap.key}.desc`)}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ── Quick Actions ── */}
      <section className="space-y-4">
        <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">{t('dashboard.pipeline')}</h3>
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
          {[
            { step: '1', key: 'ingest',   icon: 'cloud_upload',   route: '/ingestion' },
            { step: '2', key: 'generate', icon: 'dataset',        route: '/ingestion' },
            { step: '3', key: 'annotate', icon: 'rate_review',    route: '/documents' },
            { step: '4', key: 'finetune', icon: 'model_training', route: '/fine-tuning' },
            { step: '5', key: 'query',    icon: 'chat',           route: '/playground' },
          ].map(action => (
            <button
              key={action.step}
              onClick={() => navigate(action.route)}
              className="bg-surface-container p-5 text-left hover:bg-surface-container-high transition-colors group border border-transparent hover:border-primary/20"
            >
              <div className="flex items-start justify-between mb-3">
                <div className="w-6 h-6 bg-outline-variant/20 group-hover:bg-primary/10 flex items-center justify-center transition-colors">
                  <span className="font-headline font-bold text-xs text-on-surface-variant group-hover:text-primary transition-colors">
                    {action.step}
                  </span>
                </div>
                <span className="material-symbols-outlined text-outline group-hover:text-primary text-sm transition-colors">
                  {action.icon}
                </span>
              </div>
              <p className="font-headline font-bold text-sm uppercase">{t(`dashboard.actions.${action.key}.label`)}</p>
              <p className="text-[10px] text-on-surface-variant uppercase tracking-widest mt-0.5">{t(`dashboard.actions.${action.key}.sub`)}</p>
            </button>
          ))}
        </div>
      </section>

      {/* ── API Info ── */}
      {status && (
        <section className="bg-surface-container p-5 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="material-symbols-outlined text-sm text-primary">api</span>
            <div>
              <p className="text-xs font-label font-bold uppercase">{status.application}</p>
              <p className="text-[10px] text-outline font-mono">{status.version}</p>
            </div>
          </div>
          <p className="text-[10px] text-outline font-mono">
            {new Date(status.timestamp).toLocaleTimeString(i18n.language)}
          </p>
        </section>
      )}

    </div>
  );
};

export default Dashboard;
