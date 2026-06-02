import { useMemo } from 'react';
import type { FC } from 'react';
import { useQuery } from '@tanstack/react-query';
import { gedApi, ingestApi } from '../services/api';
import Skeleton from './Skeleton';
import Tooltip from './Tooltip';

// ── Strategy definitions ──────────────────────────────────────────────────────

interface StrategyMeta {
  key: string;
  name: string;
  tagline: string;
  icon: string;
  accentClass: string;
  envVar: string;
  description: string;
  tradeoff: string;
  bestFor: string;
}

const STRATEGIES: StrategyMeta[] = [
  {
    key: 'conversational',
    name: 'Conversational RAG',
    tagline: 'Chat multi-tours',
    icon: 'forum',
    accentClass: 'border-secondary/40 text-secondary bg-secondary/5',
    envVar: 'SPECTRA_CONVERSATIONAL_RAG_ENABLED=true',
    description:
      "Reformule la question actuelle en question autonome à partir de l'historique de conversation avant d'effectuer le retrieval. Le LLM reçoit aussi l'historique complet lors de la génération.",
    tradeoff: '+1 appel LLM (reformulation de la question)',
    bestFor: "Interface chat avec questions de suivi (\"et pour lui ?\", \"quand ?\"). Inutile si les questions sont toujours indépendantes.",
  },
  {
    key: 'corrective',
    name: 'Corrective RAG',
    tagline: 'Données bruitées / index imparfait',
    icon: 'fact_check',
    accentClass: 'border-error/40 text-error bg-error/5',
    envVar: 'SPECTRA_CORRECTIVE_RAG_ENABLED=true',
    description:
      "Évalue la pertinence de chaque chunk récupéré (RELEVANT / AMBIGUOUS / IRRELEVANT) en un seul appel LLM batch. Les chunks hors-sujet sont filtrés avant la génération, ce qui réduit les hallucinations liées au bruit.",
    tradeoff: '+1 appel LLM batch (N chunks évalués en une fois)',
    bestFor: "Score qualité moyen des documents < 60 %. OCR imparfait, PDF mal structurés, bases de données exportées brutes.",
  },
  {
    key: 'adaptive',
    name: 'Adaptive RAG',
    tagline: 'Workloads variés / maîtrise des coûts',
    icon: 'route',
    accentClass: 'border-primary/40 text-primary bg-primary/5',
    envVar: 'SPECTRA_ADAPTIVE_RAG_ENABLED=true',
    description:
      "Classifie chaque requête (DIRECT / STANDARD / AGENTIC) avant de la router vers la stratégie optimale. Les questions générales n'effectuent aucun retrieval. Les questions complexes activent l'Agentic RAG.",
    tradeoff: '+1 appel LLM court (classification) — économise le retrieval sur les requêtes DIRECT',
    bestFor: "Corpus large et varié, mélange de questions factuelles et générales. Permet de diviser par 2 la latence sur les requêtes simples.",
  },
  {
    key: 'self_rag',
    name: 'Self-RAG',
    tagline: 'Haute fiabilité / zéro hallucination',
    icon: 'verified',
    accentClass: 'border-primary/30 text-primary bg-primary/5',
    envVar: 'SPECTRA_SELF_RAG_ENABLED=true',
    description:
      "Auto-évalue la réponse générée via trois tokens de réflexion : ISREL (chunks pertinents ?), ISSUP (réponse fondée sur les sources ?), ISUSE (réponse utile ?). Si la qualité est insuffisante, relance la génération avec un prompt renforcé.",
    tradeoff: '+1 à +2 appels LLM (évaluation + raffinement éventuel)',
    bestFor: "Documents critiques (juridique, médical, réglementaire, audit) où les erreurs factuelles sont inacceptables.",
  },
  {
    key: 'agentic',
    name: 'Agentic RAG',
    tagline: 'Questions complexes / multi-étapes',
    icon: 'psychology',
    accentClass: 'border-secondary/30 text-secondary bg-secondary/5',
    envVar: 'SPECTRA_AGENTIC_RAG_ENABLED=true',
    description:
      "Boucle de raisonnement ReAct : le LLM décide lui-même s'il a besoin de plus d'information et reformule ses requêtes de recherche de façon itérative (max 3 tours). Compatible avec le Hybrid Search et le Re-ranking.",
    tradeoff: '+2 à +6 appels LLM (boucle ReAct, 3 itérations max)',
    bestFor: "Questions analytiques, comparatives ou multi-documents. Corpus volumineux avec l'information répartie sur plusieurs sources.",
  },
];

// ── Recommendation engine ─────────────────────────────────────────────────────

type Priority = 'PRIORITAIRE' | 'RECOMMANDÉ' | 'OPTIONNEL';

interface StrategyRec {
  key: string;
  priority: Priority;
  reason: string;
}

const PRIORITY_STYLES: Record<Priority, string> = {
  PRIORITAIRE: 'border-error/40 text-error bg-error/5',
  RECOMMANDÉ:  'border-primary/40 text-primary bg-primary/5',
  OPTIONNEL:   'border-outline-variant/30 text-outline',
};

function computeRecommendations(
  stats: Record<string, any> | null,
  formats: string[],
): StrategyRec[] {
  if (!stats || stats.total === 0) return [];

  const total      = stats.total       as number ?? 0;
  const avgQ       = (stats.avgQualityScore as number | null) ?? null;
  const totalChunks = stats.totalChunks as number ?? 0;
  const qualDist   = stats.qualityDistribution as Record<string, number> ?? {};
  const lowQCount  = (qualDist['0.00-0.25'] ?? 0) + (qualDist['0.25-0.50'] ?? 0);

  const recs: StrategyRec[] = [];

  // ── Conversational : toujours utile dans le chat ────────────────────────────
  recs.push({
    key: 'conversational',
    priority: 'RECOMMANDÉ',
    reason: "Interface chat détectée — améliore le retrieval sur les questions de suivi.",
  });

  // ── Corrective : données bruitées ──────────────────────────────────────────
  if (avgQ !== null && avgQ < 0.65) {
    recs.push({
      key: 'corrective',
      priority: avgQ < 0.45 ? 'PRIORITAIRE' : 'RECOMMANDÉ',
      reason: `Score qualité moyen ${(avgQ * 100).toFixed(0)} % — ${lowQCount} document(s) sous 50 %. Le filtrage correctif réduira les hallucinations.`,
    });
  }

  // ── Adaptive : corpus large ou varié ─────────────────────────────────────
  const formatVariety = formats.length;
  if (total >= 15 || formatVariety >= 3) {
    recs.push({
      key: 'adaptive',
      priority: total >= 50 ? 'PRIORITAIRE' : 'RECOMMANDÉ',
      reason: `${total} document(s) • ${formatVariety} format(s) — le routage adaptatif optimise la latence et les coûts selon la complexité de la question.`,
    });
  }

  // ── Self-RAG : corpus de qualité mais fiabilité requise ──────────────────
  if (avgQ !== null && avgQ >= 0.65 && totalChunks >= 100) {
    recs.push({
      key: 'self_rag',
      priority: 'RECOMMANDÉ',
      reason: `Corpus de qualité (${(avgQ * 100).toFixed(0)} %) avec ${totalChunks} chunks — l'auto-évaluation maximise la fiabilité des réponses.`,
    });
  }

  // ── Agentic : corpus volumineux ───────────────────────────────────────────
  if (totalChunks >= 500) {
    recs.push({
      key: 'agentic',
      priority: totalChunks >= 2000 ? 'PRIORITAIRE' : 'RECOMMANDÉ',
      reason: `${totalChunks} chunks indexés — la recherche multi-étapes ReAct couvre mieux les questions complexes nécessitant plusieurs sources.`,
    });
  }

  // Dédoublonnage et tri
  const seen = new Set<string>();
  const order: Record<Priority, number> = { PRIORITAIRE: 0, RECOMMANDÉ: 1, OPTIONNEL: 2 };
  return recs
    .filter(r => { if (seen.has(r.key)) return false; seen.add(r.key); return true; })
    .sort((a, b) => order[a.priority] - order[b.priority]);
}

// ── Corpus profile label ──────────────────────────────────────────────────────

function corpusLabel(total: number, totalChunks: number): string {
  if (total === 0)       return 'Aucun document indexé';
  if (total <= 5)        return 'Corpus minimal';
  if (total <= 20)       return 'Corpus léger';
  if (total <= 100)      return 'Corpus modéré';
  if (totalChunks >= 2000) return 'Corpus large';
  return 'Corpus substantiel';
}

// ── Component ─────────────────────────────────────────────────────────────────

interface Props {
  open: boolean;
  onClose: () => void;
}

const RagAdvisor: FC<Props> = ({ open, onClose }) => {
  const { data: stats, isLoading: loadingStats } = useQuery<Record<string, any>>({
    queryKey: ['ged-stats-advisor'],
    queryFn: () => gedApi.getStats().then(r => r.data),
    enabled: open,
    staleTime: 30_000,
  });

  const { data: files } = useQuery<any[]>({
    queryKey: ['ingest-files-advisor'],
    queryFn: () => ingestApi.getHistory().then(r => r.data),
    enabled: open,
    staleTime: 30_000,
  });

  const formats = useMemo(() => {
    if (!files) return [];
    const seen = new Set<string>();
    files.forEach((f: any) => {
      const ext = (f.fileName as string).split('.').pop()?.toLowerCase() ?? f.format;
      if (ext) seen.add(ext);
    });
    return Array.from(seen);
  }, [files]);

  const recommendations = useMemo(() => computeRecommendations(stats ?? null, formats), [stats, formats]);

  const total       = stats?.total       as number ?? 0;
  const avgQ        = stats?.avgQualityScore as number | null ?? null;
  const totalChunks = stats?.totalChunks  as number ?? 0;
  const qualDist    = stats?.qualityDistribution as Record<string, number> ?? {};

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/60 backdrop-blur-sm z-40 animate-in fade-in duration-200"
        onClick={onClose}
      />

      {/* Panel */}
      <div className="fixed inset-y-0 right-0 w-full lg:w-[560px] bg-surface-container-high shadow-[-20px_0_40px_rgba(0,0,0,0.5)] z-50 animate-in slide-in-from-right duration-300 border-l border-outline-variant/20 flex flex-col">

        {/* Header */}
        <header className="p-6 border-b border-outline-variant/20 flex justify-between items-start shrink-0">
          <div>
            <p className="text-[9px] font-label uppercase tracking-widest text-outline mb-1">Intelligence Pipeline</p>
            <h3 className="font-headline text-xl font-bold tracking-tight uppercase">Conseiller RAG</h3>
            <p className="text-[10px] text-on-surface-variant mt-1">
              Recommandations basées sur votre corpus actuel
            </p>
          </div>
          <button onClick={onClose} className="w-10 h-10 flex items-center justify-center hover:bg-surface-variant transition-colors shrink-0 mt-1">
            <span className="material-symbols-outlined">close</span>
          </button>
        </header>

        <div className="flex-1 overflow-y-auto custom-scrollbar">

          {/* ── Analyse du corpus ─────────────────────────────────────────── */}
          <section className="p-6 border-b border-outline-variant/10">
            <p className="font-label text-[9px] uppercase tracking-widest text-outline mb-4">Analyse du corpus</p>

            {loadingStats ? (
              <div className="space-y-3">
                <Skeleton className="h-16" />
                <Skeleton className="h-10" />
              </div>
            ) : total === 0 ? (
              <div className="flex items-center gap-3 p-4 border border-outline-variant/20 bg-surface-container-lowest">
                <span className="material-symbols-outlined text-outline text-2xl">inbox</span>
                <div>
                  <p className="font-headline font-bold text-sm">Aucun document indexé</p>
                  <p className="text-[10px] text-on-surface-variant mt-0.5">Ingérez des documents via la page <strong>GED / Database</strong> pour obtenir des recommandations.</p>
                </div>
              </div>
            ) : (
              <>
                {/* Stats cards */}
                <div className="grid grid-cols-3 gap-3 mb-4">
                  <div className="p-3 bg-surface-container-lowest border-l-2 border-primary">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Documents</p>
                    <p className="font-headline font-bold text-2xl">{total}</p>
                    <p className="text-[8px] text-on-surface-variant mt-0.5">{corpusLabel(total, totalChunks)}</p>
                  </div>
                  <div className="p-3 bg-surface-container-lowest border-l-2 border-secondary">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Qualité moy.</p>
                    <p className={`font-headline font-bold text-2xl ${avgQ !== null && avgQ < 0.5 ? 'text-error' : avgQ !== null && avgQ >= 0.7 ? 'text-primary' : ''}`}>
                      {avgQ !== null ? `${(avgQ * 100).toFixed(0)}%` : '—'}
                    </p>
                    <p className="text-[8px] text-on-surface-variant mt-0.5">
                      {avgQ !== null && avgQ < 0.5 ? 'Index bruité' : avgQ !== null && avgQ >= 0.7 ? 'Bonne qualité' : 'Qualité moyenne'}
                    </p>
                  </div>
                  <div className="p-3 bg-surface-container-lowest border-l-2 border-outline-variant">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Chunks</p>
                    <p className="font-headline font-bold text-2xl">{totalChunks.toLocaleString('fr-FR')}</p>
                    <p className="text-[8px] text-on-surface-variant mt-0.5">{formats.length} format(s)</p>
                  </div>
                </div>

                {/* Quality histogram */}
                {Object.keys(qualDist).length > 0 && (
                  <div className="mb-4">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-2">Distribution de qualité</p>
                    <div className="flex gap-1 h-6">
                      {[
                        { key: '0.00-0.25', color: 'bg-error/60',            label: '0–25%' },
                        { key: '0.25-0.50', color: 'bg-error/30',            label: '25–50%' },
                        { key: '0.50-0.75', color: 'bg-secondary/40',        label: '50–75%' },
                        { key: '0.75-1.00', color: 'bg-primary/60',          label: '75–100%' },
                      ].map(({ key, color, label }) => {
                        const count = qualDist[key] ?? 0;
                        const pct = total > 0 ? (count / total) * 100 : 0;
                        return pct > 0 ? (
                          <Tooltip key={key} content={`${label} : ${count} doc(s)`}>
                            <div style={{ width: `${pct}%` }} className={`${color} h-full min-w-[4px] rounded-sm`} />
                          </Tooltip>
                        ) : null;
                      })}
                    </div>
                  </div>
                )}

                {/* Formats */}
                {formats.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {formats.map(f => (
                      <span key={f} className="text-[8px] border border-outline-variant/30 px-1.5 py-0.5 uppercase font-mono text-outline-variant">
                        .{f}
                      </span>
                    ))}
                  </div>
                )}
              </>
            )}
          </section>

          {/* ── Recommandations ───────────────────────────────────────────── */}
          {recommendations.length > 0 && (
            <section className="p-6 border-b border-outline-variant/10">
              <p className="font-label text-[9px] uppercase tracking-widest text-outline mb-4">
                Recommandations pour ce corpus
              </p>
              <div className="space-y-3">
                {recommendations.map(rec => {
                  const meta = STRATEGIES.find(s => s.key === rec.key)!;
                  return (
                    <div key={rec.key} className={`p-4 border ${PRIORITY_STYLES[rec.priority]} space-y-2`}>
                      <div className="flex items-center justify-between gap-3">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className="material-symbols-outlined text-[18px] shrink-0">{meta.icon}</span>
                          <p className="font-headline font-bold text-sm tracking-tight truncate">{meta.name}</p>
                        </div>
                        <span className={`text-[8px] font-bold px-1.5 py-0.5 border uppercase tracking-wider shrink-0 ${PRIORITY_STYLES[rec.priority]}`}>
                          {rec.priority}
                        </span>
                      </div>
                      <p className="text-[10px] text-on-surface-variant leading-relaxed">{rec.reason}</p>
                      <button
                        onClick={() => navigator.clipboard.writeText(meta.envVar)}
                        className="flex items-center gap-2 text-[9px] font-mono border border-outline-variant/20 px-2 py-1 hover:border-primary/40 hover:text-primary transition-colors group w-full"
                      >
                        <span className="flex-1 text-left truncate">{meta.envVar}</span>
                        <span className="material-symbols-outlined text-[12px] text-outline group-hover:text-primary transition-colors shrink-0">content_copy</span>
                      </button>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* ── Guide complet des stratégies ──────────────────────────────── */}
          <section className="p-6">
            <p className="font-label text-[9px] uppercase tracking-widest text-outline mb-4">
              Guide des stratégies
            </p>
            <div className="space-y-4">
              {STRATEGIES.map(s => {
                const isRecommended = recommendations.some(r => r.key === s.key);
                return (
                  <details key={s.key} className={`border ${isRecommended ? s.accentClass : 'border-outline-variant/20'} group`}>
                    <summary className="flex items-center gap-3 px-4 py-3 cursor-pointer list-none select-none hover:bg-surface-container-high/50 transition-colors">
                      <span className={`material-symbols-outlined text-[18px] shrink-0 ${isRecommended ? '' : 'text-on-surface-variant'}`}>
                        {s.icon}
                      </span>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="font-headline font-bold text-sm tracking-tight">{s.name}</p>
                          {isRecommended && (
                            <span className="text-[7px] font-bold px-1 py-0.5 border border-primary/30 text-primary uppercase tracking-wider bg-primary/5">
                              ✓ recommandé
                            </span>
                          )}
                        </div>
                        <p className="text-[9px] uppercase tracking-widest text-on-surface-variant mt-0.5">{s.tagline}</p>
                      </div>
                      <span className="material-symbols-outlined text-[16px] text-on-surface-variant group-open:rotate-180 transition-transform shrink-0">
                        expand_more
                      </span>
                    </summary>

                    <div className="px-4 pb-4 pt-1 space-y-3 border-t border-outline-variant/20">
                      <p className="text-xs text-on-surface-variant leading-relaxed">{s.description}</p>

                      <div className="grid grid-cols-1 gap-2">
                        <div className="flex items-start gap-2">
                          <span className="material-symbols-outlined text-[13px] text-outline mt-0.5 shrink-0">speed</span>
                          <div>
                            <p className="text-[8px] uppercase tracking-widest text-outline mb-0.5">Surcoût</p>
                            <p className="text-[10px] text-on-surface-variant">{s.tradeoff}</p>
                          </div>
                        </div>
                        <div className="flex items-start gap-2">
                          <span className="material-symbols-outlined text-[13px] text-outline mt-0.5 shrink-0">tips_and_updates</span>
                          <div>
                            <p className="text-[8px] uppercase tracking-widest text-outline mb-0.5">Quand l'activer</p>
                            <p className="text-[10px] text-on-surface-variant">{s.bestFor}</p>
                          </div>
                        </div>
                      </div>

                      <button
                        onClick={() => navigator.clipboard.writeText(s.envVar)}
                        className="flex items-center gap-2 text-[9px] font-mono border border-outline-variant/20 px-2 py-1.5 hover:border-primary/40 hover:text-primary transition-colors group w-full"
                      >
                        <span className="flex-1 text-left">{s.envVar}</span>
                        <span className="material-symbols-outlined text-[12px] text-outline group-hover:text-primary transition-colors">content_copy</span>
                      </button>
                    </div>
                  </details>
                );
              })}
            </div>

            {/* Compatibilité matrix */}
            <div className="mt-6 p-4 bg-surface-container-lowest border border-outline-variant/10">
              <p className="text-[9px] font-bold uppercase tracking-widest text-outline mb-3">
                Compatibilité — cumul des modules
              </p>
              <div className="space-y-1.5 text-[9px] text-on-surface-variant">
                {[
                  { combo: 'Conversational + Adaptive', note: 'Optimal — reformule avant de router.' },
                  { combo: 'Corrective + Reranker',     note: 'Recommandé — double filtrage de pertinence.' },
                  { combo: 'Adaptive → Agentic',        note: 'Le classifier active l\'Agentic si AGENTIC.' },
                  { combo: 'Self-RAG + Agentic',        note: 'Éviter — double boucle LLM, latence élevée.' },
                  { combo: 'Tous activés',              note: 'Déconseillé en production — réservez aux tests.' },
                ].map(({ combo, note }) => (
                  <div key={combo} className="flex items-start gap-2">
                    <span className="font-mono text-primary shrink-0">{combo}</span>
                    <span className="text-outline">—</span>
                    <span>{note}</span>
                  </div>
                ))}
              </div>
            </div>
          </section>
        </div>
      </div>
    </>
  );
};

export default RagAdvisor;
