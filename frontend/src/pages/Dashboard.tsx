import { useState, useEffect, useCallback } from 'react';
import type { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { useStatus } from '../hooks/useStatus';
import { datasetApi, gedApi, commentApi } from '../services/api';
import Skeleton from '../components/Skeleton';
import Tooltip from '../components/Tooltip';

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

const Dashboard: FC = () => {
  const navigate = useNavigate();
  const { status, loading, error } = useStatus();
  const [stats, setStats]         = useState<DatasetStats | null>(null);
  const [gedStats, setGedStats]   = useState<GedStats | null>(null);
  const [commentStats, setCommentStats] = useState<CommentStats | null>(null);
  const [statsLoading, setStatsLoading] = useState(true);

  const loadStats = useCallback(async () => {
    try {
      const [dsRes, gedRes] = await Promise.allSettled([
        datasetApi.getStats(),
        gedApi.getStats(),
      ]);
      if (dsRes.status === 'fulfilled') setStats(dsRes.value.data);
      if (gedRes.status === 'fulfilled') {
        const g = gedRes.value.data;
        setGedStats(g);
        // Derive comment stats from GED stats if available
        if (g.commentStats) setCommentStats(g.commentStats);
      }
    } catch {
      // ignore
    } finally {
      setStatsLoading(false);
    }
  }, []);

  // Load comment stats separately (new endpoint)
  const loadCommentStats = useCallback(async () => {
    try {
      const docs = await gedApi.listDocuments({ size: 500 });
      const allDocs = docs.data?.content ?? [];
      if (allDocs.length === 0) return;
      const commentResponses = await Promise.allSettled(
        allDocs.slice(0, 20).map((d: { sha256: string }) => commentApi.list(d.sha256))
      );
      let total = 0, approved = 0, rejected = 0, aiGenerated = 0;
      commentResponses.forEach(r => {
        if (r.status === 'fulfilled') {
          const list: any[] = r.value.data ?? [];
          total += list.length;
          approved += list.filter((c: any) => c.rating === 'APPROVED').length;
          rejected += list.filter((c: any) => c.rating === 'REJECTED').length;
          aiGenerated += list.filter((c: any) => c.type === 'AI_GENERATED').length;
        }
      });
      setCommentStats({ total, approved, rejected, aiGenerated });
    } catch {
      // ignore — comment stats are non-critical
    }
  }, []);

  useEffect(() => {
    loadStats();
    loadCommentStats();
    const id = setInterval(() => { loadStats(); }, 30_000);
    return () => clearInterval(id);
  }, [loadStats, loadCommentStats]);

  useEffect(() => {
    if (error) toast.error('Connection Failed', { description: 'Unable to reach Spectra API.' });
  }, [error]);

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
        <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">System Overview</p>
        <h2 className="font-headline text-3xl font-bold tracking-tighter">DASHBOARD</h2>
      </header>

      {/* ── Service Health ── */}
      <section className="space-y-4">
        <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">Service Health</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">

          <div className="bg-surface-container p-6 flex items-center justify-between">
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
                <p className="text-[10px] text-on-surface-variant uppercase tracking-widest">LLM Inference · llama.cpp</p>
              </div>
            </div>
            <div className="text-right">
              {loading ? <Skeleton className="h-4 w-16" /> : (
                <>
                  <p className={`text-[10px] font-bold uppercase tracking-widest ${chatSvc?.available ? 'text-primary' : 'text-error'}`}>
                    {chatSvc?.available ? 'Online' : 'Offline'}
                  </p>
                  {chatSvc?.details?.activeModel && (
                    <p className="text-[9px] text-outline font-mono mt-0.5 max-w-[120px] truncate" title={chatSvc.details.activeModel}>
                      {chatSvc.details.activeModel}
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
                <p className="text-[10px] text-on-surface-variant uppercase tracking-widest">Embeddings · llama.cpp</p>
              </div>
            </div>
            <div className="text-right">
              {loading ? <Skeleton className="h-4 w-16" /> : !embedSvc ? (
                <p className="text-[10px] font-bold uppercase tracking-widest text-outline">N/A</p>
              ) : (
                <>
                  <p className={`text-[10px] font-bold uppercase tracking-widest ${embedSvc.available ? 'text-secondary' : 'text-error'}`}>
                    {embedSvc.available ? 'Online' : 'Offline'}
                  </p>
                  {embedSvc?.details?.activeModel && (
                    <p className="text-[9px] text-outline font-mono mt-0.5 max-w-[120px] truncate" title={embedSvc.details.activeModel}>
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
                <p className="text-[10px] text-on-surface-variant uppercase tracking-widest">Vector Storage · API v2</p>
              </div>
            </div>
            <div className="text-right">
              {loading ? <Skeleton className="h-4 w-16" /> : (
                <>
                  <p className={`text-[10px] font-bold uppercase tracking-widest ${chromadb?.available ? 'text-primary' : 'text-error'}`}>
                    {chromadb?.available ? 'Online' : 'Offline'}
                  </p>
                  {!statsLoading && (stats?.chunksInStore ?? 0) > 0 && (
                    <p className="text-[9px] text-outline font-mono mt-0.5">{stats!.chunksInStore} chunks</p>
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
          <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">Getting Started</h3>

          <div className="bg-surface-container p-5 space-y-3">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-3">Prérequis</p>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {[
                { ok: chatSvc?.available ?? false, label: 'Modèle de chat', hint: 'Placez model.gguf dans data/fine-tuning/merged/', icon: 'memory' },
                { ok: embedSvc?.available ?? false, label: "Modèle d'embedding", hint: 'Placez embed.gguf dans data/models/ — ou lancez setup.bat --download-embed', icon: 'hub' },
                { ok: chromadb?.available ?? false, label: 'ChromaDB', hint: 'Vérifiez docker compose ps', icon: 'database' },
              ].map(item => (
                <div key={item.label} className={`flex items-start gap-3 p-3 border ${item.ok ? 'border-primary/20 bg-primary/5' : 'border-error/20 bg-error/5'}`}>
                  <span className={`material-symbols-outlined text-sm mt-0.5 ${item.ok ? 'text-primary' : 'text-error'}`}>
                    {item.ok ? 'check_circle' : 'cancel'}
                  </span>
                  <div className="min-w-0">
                    <p className="font-label text-[10px] font-bold uppercase tracking-widest">{item.label}</p>
                    {!item.ok && <p className="text-[9px] text-on-surface-variant mt-0.5 leading-relaxed">{item.hint}</p>}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {[
              { num: '1', title: 'Ingérez vos documents', desc: 'PDF, DOCX, TXT ou URLs — glissez vos fichiers dans Dataset Pipelines ou lancez adddoc.bat examples pour tester.', action: 'Aller à Dataset Pipelines', route: '/datasets', icon: 'cloud_upload' },
              { num: '2', title: 'Générez le dataset', desc: 'Spectra génère automatiquement des paires Q/R à partir de vos documents. Comptez 30–120 s/chunk sur CPU.', action: 'Aller à Dataset Pipelines', route: '/datasets', icon: 'dataset' },
              { num: '3', title: 'Lancez le fine-tuning', desc: 'Choisissez une recette (ex. "CPU Rapide") et affinez le modèle sur votre domaine.', action: 'Aller à Fine-Tuning', route: '/fine-tuning', icon: 'model_training' },
            ].map(step => (
              <div key={step.num} className="bg-surface-container p-5 space-y-3">
                <div className="flex items-center gap-3">
                  <div className="w-7 h-7 bg-primary/10 flex items-center justify-center shrink-0">
                    <span className="font-headline font-bold text-xs text-primary">{step.num}</span>
                  </div>
                  <p className="font-headline font-bold text-sm uppercase">{step.title}</p>
                </div>
                <p className="text-[10px] text-on-surface-variant leading-relaxed">{step.desc}</p>
                <button onClick={() => navigate(step.route)} className="w-full text-left text-[9px] font-label font-bold uppercase tracking-widest text-primary hover:text-primary/70 transition-colors flex items-center gap-1">
                  <span className="material-symbols-outlined text-[11px]">arrow_forward</span>
                  {step.action}
                </button>
              </div>
            ))}
          </div>

          <div className="p-4 border border-outline-variant/20 bg-surface-container">
            <p className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant flex items-center gap-2">
              <span className="material-symbols-outlined text-sm text-outline">tip</span>
              Nouveau ? Lancez <code className="font-mono bg-surface-container-high px-1">adddoc.bat examples</code> pour ingérer les documents de démonstration et valider le pipeline en 5 minutes.
            </p>
          </div>
        </section>
      )}

      {/* ── Knowledge Base Stats ── */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">Knowledge Base</h3>
          <Tooltip content="Données stockées dans ChromaDB et en mémoire API.">
            <span className="material-symbols-outlined text-sm text-outline cursor-help">info</span>
          </Tooltip>
        </div>
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">

          <div className="bg-surface-container p-5 border-t-2 border-primary">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Chunks in Store</p>
            {statsLoading ? <Skeleton className="h-9 w-16" /> : (
              <p className="font-headline font-bold text-3xl">{stats?.chunksInStore ?? 0}</p>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-secondary">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Training Pairs</p>
            {statsLoading ? <Skeleton className="h-9 w-16" /> : (
              <p className="font-headline font-bold text-3xl">{stats?.totalPairs ?? 0}</p>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Avg Confidence</p>
            {statsLoading ? <Skeleton className="h-9 w-16" /> : (
              <p className="font-headline font-bold text-3xl">
                {stats && stats.avgConfidence > 0 ? (stats.avgConfidence * 100).toFixed(0) + '%' : '—'}
              </p>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Categories</p>
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
            <p className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">
              {pipelineReady
                ? `Pipeline prêt — ${stats!.chunksInStore} chunks · ${stats!.totalPairs} paires · modèle fine-tunable`
                : (stats?.chunksInStore ?? 0) > 0
                  ? 'Chunks indexés — lancez la génération de dataset (étape 2)'
                  : 'Base vide — ingérez des documents pour démarrer (étape 1)'}
            </p>
          </div>
        )}
      </section>

      {/* ── GED + Comment Stats ── */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">Documents & Annotations</h3>
          <button
            onClick={() => navigate('/pipelines')}
            className="text-[9px] font-label font-bold uppercase tracking-widest text-primary hover:text-primary/70 transition-colors flex items-center gap-1"
          >
            <span className="material-symbols-outlined text-[11px]">arrow_forward</span>
            Gérer les documents
          </button>
        </div>

        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">

          <div className="bg-surface-container p-5 border-t-2 border-primary/60">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Documents GED</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl">{totalDocs}</p>
                {gedStats?.byLifecycle && (
                  <div className="flex gap-1 mt-2 flex-wrap">
                    {Object.entries(gedStats.byLifecycle).map(([lc, n]) => (
                      <span key={lc} className="text-[7px] font-bold uppercase px-1 py-0.5 border border-outline-variant/20 text-outline">
                        {lc.slice(0, 3)} {String(n)}
                      </span>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-secondary/60">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Commentaires IA</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl">{commentStats?.aiGenerated ?? 0}</p>
                <p className="text-[9px] text-on-surface-variant mt-1">
                  {commentStats?.total ?? 0} total (humains + IA)
                </p>
              </>
            )}
          </div>

          <div className="bg-surface-container p-5 border-t-2 border-primary/40">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Évalués</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className="font-headline font-bold text-3xl">
                  {(commentStats?.approved ?? 0) + (commentStats?.rejected ?? 0)}
                </p>
                <div className="flex gap-2 mt-2">
                  <span className="text-[8px] font-bold text-primary">👍 {commentStats?.approved ?? 0}</span>
                  <span className="text-[8px] font-bold text-error">👎 {commentStats?.rejected ?? 0}</span>
                </div>
              </>
            )}
          </div>

          <div className={`bg-surface-container p-5 border-t-2 ${dpoPairsReady ? 'border-primary' : 'border-outline-variant/30'}`}>
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">DPO Prêt</p>
            {statsLoading ? <Skeleton className="h-9 w-12" /> : (
              <>
                <p className={`font-headline font-bold text-3xl ${dpoPairsReady ? 'text-primary' : 'text-outline'}`}>
                  {dpoPairsReady ? '✓' : '—'}
                </p>
                <p className="text-[9px] text-on-surface-variant mt-1">
                  {dpoPairsReady ? `${commentStats!.approved} paires exportables` : 'Aucun commentaire approuvé'}
                </p>
              </>
            )}
          </div>

        </div>

        {/* Comment DPO hint */}
        {!statsLoading && (commentStats?.aiGenerated ?? 0) > 0 && (commentStats?.approved ?? 0) === 0 && (
          <div className="p-4 border border-secondary/20 bg-secondary/5 flex items-start gap-3">
            <span className="material-symbols-outlined text-sm text-secondary mt-0.5 shrink-0">rate_review</span>
            <p className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">
              {commentStats!.aiGenerated} commentaire(s) IA généré(s) — évaluez-les (👍/👎) dans la fiche document pour constituer vos paires DPO.
            </p>
          </div>
        )}
        {!statsLoading && dpoPairsReady && (
          <div className="p-4 border border-primary/30 bg-primary/5 flex items-start gap-3">
            <span className="material-symbols-outlined text-sm text-primary mt-0.5 shrink-0">check_circle</span>
            <p className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">
              {commentStats!.approved} paire(s) DPO disponible(s) — exportez-les depuis la page Database puis relancez un fine-tuning avec alignement DPO.
            </p>
          </div>
        )}
      </section>

      {/* ── RAG Capabilities ── */}
      <section className="space-y-4">
        <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">Capacités RAG</h3>
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-2">
          {[
            { label: 'Hybrid Search',   icon: 'merge',          desc: 'BM25 + vecteurs RRF',          color: 'primary' },
            { label: 'Re-ranking',      icon: 'sort',           desc: 'Cross-Encoder 2 étapes',       color: 'secondary' },
            { label: 'Multi-Query',     icon: 'dynamic_feed',   desc: 'N reformulations + fusion',    color: 'primary' },
            { label: 'Agentic RAG',     icon: 'psychology',     desc: 'Boucle ReAct multi-hop',       color: 'secondary' },
            { label: 'Corrective RAG',  icon: 'fact_check',     desc: 'Grading LLM des chunks',       color: 'primary' },
            { label: 'Commentaires IA', icon: 'rate_review',    desc: 'RAG → commentaire → DPO',      color: 'secondary' },
          ].map(cap => (
            <div key={cap.label} className={`bg-surface-container p-4 border border-outline-variant/10 hover:border-${cap.color}/30 transition-colors group`}>
              <span className={`material-symbols-outlined text-base text-outline group-hover:text-${cap.color} transition-colors`}>{cap.icon}</span>
              <p className="font-headline font-bold text-[10px] uppercase mt-2">{cap.label}</p>
              <p className="text-[9px] text-on-surface-variant mt-1 leading-relaxed">{cap.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ── Quick Actions ── */}
      <section className="space-y-4">
        <h3 className="font-headline text-sm font-bold uppercase tracking-tight text-on-surface-variant">Pipeline</h3>
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
          {[
            { step: '1', label: 'Ingest',      sub: 'Upload documents',  icon: 'cloud_upload',   route: '/datasets' },
            { step: '2', label: 'Generate',    sub: 'Build dataset',     icon: 'dataset',        route: '/datasets' },
            { step: '3', label: 'Annotate',    sub: 'RAG comments + DPO',icon: 'rate_review',    route: '/pipelines' },
            { step: '4', label: 'Fine-Tune',   sub: 'Train model',       icon: 'model_training', route: '/fine-tuning' },
            { step: '5', label: 'Query',       sub: 'RAG playground',    icon: 'chat',           route: '/playground' },
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
              <p className="font-headline font-bold text-sm uppercase">{action.label}</p>
              <p className="text-[9px] text-on-surface-variant uppercase tracking-widest mt-0.5">{action.sub}</p>
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
              <p className="text-[9px] text-outline font-mono">{status.version}</p>
            </div>
          </div>
          <p className="text-[9px] text-outline font-mono">
            {new Date(status.timestamp).toLocaleTimeString('fr-FR')}
          </p>
        </section>
      )}

    </div>
  );
};

export default Dashboard;
