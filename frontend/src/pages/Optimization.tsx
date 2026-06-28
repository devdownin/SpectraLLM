import { useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ablationApi, fineTuningApi } from '../services/api';
import AblationCharts from '../components/charts/AblationCharts';
import type {
  AblationArmConfig,
  AblationArmReport,
  AblationReport,
  RagOverrides,
} from '../types/api';

/**
 * Écran « Optimisation des réponses » — valide et compare l'apport de chaque option
 * d'apprentissage (fine-tuning) et d'optimisation RAG via une ablation A/B sur le benchmark
 * tenu à l'écart. Chaque bras n'active qu'un changement de plus → le delta = gain marginal.
 */

// ── Métadonnées pédagogiques des modules d'optimisation ───────────────────────

type ModuleKey = keyof RagOverrides;

interface ModuleInfo {
  key: ModuleKey;
  name: string;
  what: string;       // ce que ça fait
  gain: string;       // bénéfice attendu
  cost: string;       // coût
}

const MODULES: ModuleInfo[] = [
  { key: 'hybrid',         name: 'Recherche hybride',  what: 'Combine mots-clés (BM25) et similarité sémantique (vecteurs) via fusion RRF.', gain: 'Meilleur rappel, robuste aux termes exacts (références, codes).', cost: 'Léger (une recherche lexicale en plus).' },
  { key: 'rerank',         name: 'Re-ranking',         what: 'Réordonne les candidats avec un Cross-Encoder qui juge finement la pertinence.', gain: 'Contexte plus précis en tête → exactitude, Hit@k, MRR.', cost: 'Latence (un modèle de plus à l’inférence).' },
  { key: 'multiQuery',     name: 'Multi-Query',        what: 'Génère N reformulations de la question, récupère pour chacune, puis fusionne.', gain: 'Rappel sur questions ambiguës ou mal formulées.', cost: 'Latence (1 appel LLM + N recherches).' },
  { key: 'corrective',     name: 'Corrective RAG',     what: 'Note chaque chunk récupéré (grading LLM) et écarte les non pertinents.', gain: 'Moins de bruit dans le contexte → moins d’hallucination.', cost: 'Appels LLM de grading.' },
  { key: 'compression',    name: 'Compression contexte', what: 'Extrait uniquement les passages utiles de chaque chunk avant génération.', gain: 'Contexte dense, moins de tokens, focus.', cost: 'Appels LLM d’extraction.' },
  { key: 'selfRag',        name: 'Self-RAG',           what: 'Le modèle génère puis auto-évalue sa réponse et la raffine si besoin.', gain: 'Fidélité au contexte, qualité de formulation.', cost: 'Latence (génération + réflexion).' },
  { key: 'adaptive',       name: 'Adaptive RAG',       what: 'Classe la requête (directe / standard / agentique) et adapte le pipeline.', gain: 'Efficience : pas de retrieval inutile sur les questions simples.', cost: 'Une classification LLM par requête.' },
  { key: 'conversational', name: 'Conversational RAG', what: 'Reformule la question en question autonome à partir de l’historique.', gain: 'Pertinence du retrieval en conversation multi-tours.', cost: 'Un appel LLM de reformulation.' },
];

const MODULE_NAME: Record<string, string> = Object.fromEntries(
  MODULES.map(m => [m.key, m.name]),
);

// ── Métriques affichées (avec sens d'amélioration) ────────────────────────────

interface MetricDef {
  key: string;
  label: string;
  help: string;
  higherIsBetter: boolean;
  format: (arm: AblationArmReport) => string;
  value: (arm: AblationArmReport) => number;
  pct?: boolean;      // delta exprimé en points de %
  stdKey?: string;    // clé dans arm.stdDev pour l'écart-type
  retrieval?: boolean; // métrique de retrieval (delta masqué si non évaluée)
}

const METRICS: MetricDef[] = [
  { key: 'avgScore', label: 'Exactitude /10', stdKey: 'avgScore', help: 'Note moyenne d’exactitude attribuée par le LLM-juge sur les questions répondables.', higherIsBetter: true, value: a => a.quality.avgScore, format: a => a.quality.avgScore.toFixed(2) },
  { key: 'halluc', label: 'Hallucination', stdKey: 'hallucinationRate', help: 'Part des questions SANS réponse dans le corpus où le modèle a tout de même inventé une réponse (plus bas = mieux).', higherIsBetter: false, pct: true, value: a => a.quality.hallucinationRate, format: a => `${(a.quality.hallucinationRate * 100).toFixed(0)}%` },
  { key: 'refusal', label: 'Abstention juste', stdKey: 'refusalAccuracy', help: 'Part des questions sans réponse correctement refusées (« je ne sais pas »).', higherIsBetter: true, pct: true, value: a => a.quality.refusalAccuracy, format: a => `${(a.quality.refusalAccuracy * 100).toFixed(0)}%` },
  { key: 'hit', label: 'Hit@k', stdKey: 'hitRate', retrieval: true, help: 'Retrieval : part des questions dont une source attendue figure dans le top-k (nécessite expectedSources dans le benchmark).', higherIsBetter: true, value: a => a.retrieval.hitRate, format: a => a.retrieval.evaluatedQuestions ? a.retrieval.hitRate.toFixed(2) : '—' },
  { key: 'mrr', label: 'MRR', stdKey: 'mrr', retrieval: true, help: 'Retrieval : moyenne de 1/rang de la première source pertinente.', higherIsBetter: true, value: a => a.retrieval.mrr, format: a => a.retrieval.evaluatedQuestions ? a.retrieval.mrr.toFixed(2) : '—' },
  { key: 'recall', label: 'Recall@k', stdKey: 'recallAtK', retrieval: true, help: 'Retrieval : part moyenne des sources attendues retrouvées dans le top-k.', higherIsBetter: true, value: a => a.retrieval.recallAtK, format: a => a.retrieval.evaluatedQuestions ? a.retrieval.recallAtK.toFixed(2) : '—' },
  { key: 'tokens', label: 'Tokens contexte', stdKey: 'avgContextTokens', help: 'Coût déterministe : nombre moyen de tokens de contexte injectés dans le LLM (estimé). Plus bas = moins cher, contrairement à la latence (bruitée).', higherIsBetter: false, value: a => a.avgContextTokens, format: a => Math.round(a.avgContextTokens).toString() },
  { key: 'p50', label: 'Latence p50', stdKey: 'p50LatencyMs', help: 'Coût : latence médiane bout en bout d’une requête (ms). Bruitée sur matériel partagé → à recouper avec les tokens.', higherIsBetter: false, value: a => a.p50LatencyMs, format: a => `${Math.round(a.p50LatencyMs)}ms` },
];

// ── Statistiques d'affichage ──────────────────────────────────────────────────

/** Écart-type de la métrique pour un bras (0 si indisponible). */
function metricStd(metric: MetricDef, arm: AblationArmReport): number {
  return (metric.stdKey && arm.stdDev?.[metric.stdKey]) || 0;
}

/** ±σ formaté pour affichage sous la valeur (unité cohérente avec la métrique). */
function fmtStd(metric: MetricDef, arm: AblationArmReport): string {
  const s = metricStd(metric, arm);
  if (s <= 1e-9) return '';
  if (metric.pct) return `±${(s * 100).toFixed(1)}`;
  if (metric.key === 'p50' || metric.key === 'tokens') return `±${Math.round(s)}`;
  return `±${s.toFixed(2)}`;
}

/**
 * Un delta est « significatif » s'il dépasse le bruit combiné des deux bras
 * (√(σ_arm² + σ_base²)). Avec une seule répétition (σ=0) on ne peut pas trancher → on
 * considère le delta comme affichable normalement (non grisé).
 */
function isSignificant(metric: MetricDef, base: AblationArmReport, arm: AblationArmReport): boolean {
  const combined = Math.hypot(metricStd(metric, base), metricStd(metric, arm));
  if (combined <= 1e-9) return true;
  return Math.abs(metric.value(arm) - metric.value(base)) > combined;
}

// ── Construction des bras (presets) ───────────────────────────────────────────

const ALL_ON: RagOverrides = {
  hybrid: true, rerank: true, multiQuery: true, corrective: true,
  compression: true, selfRag: true, adaptive: true, conversational: true,
};
const ALL_OFF: RagOverrides = {
  hybrid: false, rerank: false, multiQuery: false, corrective: false,
  compression: false, selfRag: false, adaptive: false, conversational: false,
};

function ragGainArms(): AblationArmConfig[] {
  return [
    { label: 'LLM seul (sans RAG)', useRag: false, overrides: null },
    { label: 'RAG (config déploiement)', useRag: true, overrides: null },
  ];
}

/** Ablation cumulative : on part du RAG vectoriel nu, puis on ajoute un module à la fois. */
function cumulativeArms(): AblationArmConfig[] {
  const order: ModuleKey[] = ['hybrid', 'rerank', 'multiQuery', 'corrective', 'compression', 'selfRag'];
  const arms: AblationArmConfig[] = [
    { label: 'RAG vectoriel nu', useRag: true, overrides: { ...ALL_OFF } },
  ];
  const acc: RagOverrides = { ...ALL_OFF };
  for (const k of order) {
    acc[k] = true;
    arms.push({ label: `+ ${MODULE_NAME[k]}`, useRag: true, overrides: { ...acc } });
  }
  return arms;
}

/** Leave-one-out : tout activé, puis on retire un module à la fois (apport isolé de chacun). */
function leaveOneOutArms(): AblationArmConfig[] {
  const arms: AblationArmConfig[] = [
    { label: 'Tout activé', useRag: true, overrides: { ...ALL_ON } },
  ];
  for (const m of MODULES) {
    arms.push({ label: `− ${m.name}`, useRag: true, overrides: { ...ALL_ON, [m.key]: false } });
  }
  return arms;
}

type PresetKey = 'rag' | 'cumulative' | 'loo' | 'finetuning';

// ── UI helpers ────────────────────────────────────────────────────────────────

function deltaColor(delta: number, higherIsBetter: boolean): string {
  if (Math.abs(delta) < 1e-9) return 'text-on-surface-variant';
  const good = higherIsBetter ? delta > 0 : delta < 0;
  return good ? 'text-primary' : 'text-error';
}

function fmtDelta(metric: MetricDef, base: AblationArmReport, arm: AblationArmReport): string {
  if (metric.retrieval && (!base.retrieval.evaluatedQuestions || !arm.retrieval.evaluatedQuestions)) return '';
  const d = metric.value(arm) - metric.value(base);
  if (Math.abs(d) < 1e-9) return '±0';
  const sign = d > 0 ? '+' : '';
  if (metric.pct) return `${sign}${(d * 100).toFixed(0)} pts`;
  if (metric.key === 'p50') return `${sign}${Math.round(d)}ms`;
  if (metric.key === 'tokens') return `${sign}${Math.round(d)}`;
  return `${sign}${d.toFixed(2)}`;
}

// ── Export CSV ────────────────────────────────────────────────────────────────

/** Échappe un champ CSV (séparateur virgule, décimales point — lisible tableur). */
function csvCell(v: string | number): string {
  const s = String(v);
  return /[",\n;]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function buildCsv(report: AblationReport): string {
  const headers = [
    'bras', 'modele', 'rag', 'runs',
    'exactitude_sur_10', 'exactitude_std', 'hallucination_taux', 'abstention_juste_taux',
    'hit_at_k', 'mrr', 'recall_at_k', 'retrieval_questions_evaluees',
    'tokens_contexte', 'latence_p50_ms', 'latence_moyenne_ms', 'modules_declenches',
  ];
  const num = (n: number, d = 4) => Number(n.toFixed(d));
  const rows = report.arms.map(a => {
    const hasRetrieval = a.retrieval.evaluatedQuestions > 0;
    const modules = Object.entries(a.appliedCounts || {})
      .map(([k, n]) => `${MODULE_NAME[k] ?? k} x${n}`).join('; ');
    return [
      a.label, a.model, a.useRag ? 'oui' : 'non', a.runs ?? 1,
      num(a.quality.avgScore, 2), num(a.stdDev?.avgScore ?? 0, 3),
      num(a.quality.hallucinationRate), num(a.quality.refusalAccuracy),
      hasRetrieval ? num(a.retrieval.hitRate) : '',
      hasRetrieval ? num(a.retrieval.mrr) : '',
      hasRetrieval ? num(a.retrieval.recallAtK) : '',
      a.retrieval.evaluatedQuestions,
      Math.round(a.avgContextTokens), Math.round(a.p50LatencyMs), Math.round(a.avgLatencyMs), modules,
    ].map(csvCell).join(',');
  });
  // BOM pour qu'Excel lise l'UTF-8 (accents) correctement.
  return '﻿' + [headers.join(','), ...rows].join('\r\n') + '\r\n';
}

function downloadCsv(report: AblationReport): void {
  const blob = new Blob([buildCsv(report)], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
  a.href = url;
  a.download = `ablation-${stamp}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

const Optimization: FC = () => {
  const [preset, setPreset] = useState<PresetKey>('rag');
  const [models, setModels] = useState<string[]>([]);
  const [baseModel, setBaseModel] = useState<string>('');
  const [tunedModel, setTunedModel] = useState<string>('');
  const [maxChunks, setMaxChunks] = useState<number>(5);
  const [runs, setRuns] = useState<number>(1);
  const [report, setReport] = useState<AblationReport | null>(null);

  useEffect(() => {
    fineTuningApi.getModels()
      .then(res => {
        const raw = res.data ?? [];
        const names: string[] = Array.isArray(raw)
          ? raw.map((m: unknown) => (typeof m === 'string' ? m : (m as { name?: string })?.name)).filter(Boolean) as string[]
          : [];
        setModels(names);
        if (names.length) { setBaseModel(names[0]); setTunedModel(names[names.length - 1]); }
      })
      .catch(() => { /* liste de modèles optionnelle */ });
  }, []);

  const arms: AblationArmConfig[] = useMemo(() => {
    switch (preset) {
      case 'cumulative': return cumulativeArms();
      case 'loo':        return leaveOneOutArms();
      case 'finetuning': return [
        { label: `Base · ${baseModel || 'modèle 1'}`, model: baseModel || null, useRag: true, overrides: null },
        { label: `Fine-tuné · ${tunedModel || 'modèle 2'}`, model: tunedModel || null, useRag: true, overrides: null },
      ];
      case 'rag':
      default:           return ragGainArms();
    }
  }, [preset, baseModel, tunedModel]);

  const mutation = useMutation({
    mutationFn: async (): Promise<AblationReport> => {
      const res = await ablationApi.run({ arms, maxContextChunks: maxChunks, runs });
      return res.data as AblationReport;
    },
    onSuccess: (data) => { setReport(data); toast.success('Ablation terminée'); },
    onError: () => { toast.error('Échec de l’ablation', { description: 'Vérifiez que le LLM et le benchmark sont disponibles.' }); },
  });

  const baseArm = report?.arms?.[0];
  const noRetrieval = report?.arms?.every(a => !a.retrieval.evaluatedQuestions);

  return (
    <div className="space-y-8 animate-in fade-in duration-700">
      {/* Header */}
      <header>
        <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">
          Ablation A/B · benchmark tenu à l'écart
        </p>
        <h2 className="font-headline text-3xl font-bold tracking-tighter">OPTIMISATION DES RÉPONSES</h2>
        <p className="text-sm text-on-surface-variant max-w-3xl mt-2 leading-relaxed">
          Mesurez et <strong className="text-on-surface">validez l'apport réel</strong> de chaque option d'apprentissage
          (fine-tuning) et d'optimisation (modules RAG). Chaque <em>bras</em> ne change qu'une chose à la fois :
          le <strong className="text-on-surface">delta</strong> entre deux bras est le gain marginal de l'option — toujours à
          lire en regard de son coût en latence.
        </p>
      </header>

      {/* Explications des options */}
      <section className="space-y-3">
        <h3 className="font-headline text-xs uppercase tracking-widest text-on-surface-variant">Les options mesurées</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
          {MODULES.map(m => (
            <div key={m.key} className="bg-surface-container-low border border-outline-variant/10 p-4">
              <p className="font-headline text-sm font-bold mb-1">{m.name}</p>
              <p className="text-[11px] text-on-surface-variant leading-relaxed mb-2">{m.what}</p>
              <p className="text-[10px] text-primary leading-snug">▲ {m.gain}</p>
              <p className="text-[10px] text-on-surface-variant leading-snug mt-0.5">⏱ {m.cost}</p>
            </div>
          ))}
        </div>
        <p className="text-[11px] text-on-surface-variant">
          S'ajoute l'axe <strong className="text-on-surface">apprentissage</strong> : modèle de base vs modèle fine-tuné (SFT/DPO),
          comparé via le preset dédié.
        </p>
      </section>

      {/* Configuration du passage */}
      <section className="bg-surface-container-low border border-outline-variant/10 p-5 space-y-4">
        <h3 className="font-headline text-xs uppercase tracking-widest text-on-surface-variant">Protocole</h3>
        <div className="flex flex-wrap gap-2">
          {([
            ['rag', 'Gain du RAG', 'LLM seul vs RAG'],
            ['cumulative', 'Ablation cumulative', 'on ajoute un module à la fois'],
            ['loo', 'Leave-one-out', 'tout activé, on retire un module'],
            ['finetuning', 'Gain du fine-tuning', 'base vs fine-tuné'],
          ] as [PresetKey, string, string][]).map(([k, label, sub]) => (
            <button
              key={k}
              onClick={() => setPreset(k)}
              className={`px-3 py-2 text-left border transition-colors ${preset === k
                ? 'border-primary bg-primary/10 text-on-surface'
                : 'border-outline-variant/20 text-on-surface-variant hover:border-outline-variant/40'}`}
            >
              <span className="block font-headline text-xs font-bold">{label}</span>
              <span className="block text-[10px] text-on-surface-variant">{sub}</span>
            </button>
          ))}
        </div>

        {preset === 'finetuning' && (
          <div className="flex flex-wrap gap-4">
            <label className="text-xs text-on-surface-variant">
              Modèle de base
              <select value={baseModel} onChange={e => setBaseModel(e.target.value)}
                className="block mt-1 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs">
                {models.length === 0 && <option value="">(modèle actif)</option>}
                {models.map(m => <option key={m} value={m}>{m}</option>)}
              </select>
            </label>
            <label className="text-xs text-on-surface-variant">
              Modèle fine-tuné
              <select value={tunedModel} onChange={e => setTunedModel(e.target.value)}
                className="block mt-1 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs">
                {models.length === 0 && <option value="">(modèle actif)</option>}
                {models.map(m => <option key={m} value={m}>{m}</option>)}
              </select>
            </label>
          </div>
        )}

        {/* Aperçu des bras */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Bras :</span>
          {arms.map((a, i) => (
            <span key={i} className="px-2 py-1 bg-surface-container border border-outline-variant/20 text-[11px] text-on-surface">
              {a.label}
            </span>
          ))}
        </div>

        <div className="flex items-center gap-4 flex-wrap">
          <label className="text-xs text-on-surface-variant flex items-center gap-2">
            top-k contexte
            <input type="number" min={1} max={20} value={maxChunks}
              onChange={e => setMaxChunks(Math.max(1, Math.min(20, Number(e.target.value) || 5)))}
              className="w-16 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs" />
          </label>
          <label className="text-xs text-on-surface-variant flex items-center gap-2" title="Répétitions par bras pour estimer le bruit (moyenne ± écart-type). ≥3 pour fiabiliser les deltas.">
            répétitions
            <input type="number" min={1} max={10} value={runs}
              onChange={e => setRuns(Math.max(1, Math.min(10, Number(e.target.value) || 1)))}
              className="w-16 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs" />
          </label>
          <button
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
            className="px-5 py-2 bg-primary text-on-primary font-headline text-xs uppercase tracking-widest disabled:opacity-50 hover:bg-primary/90 transition-colors"
          >
            {mutation.isPending ? 'Ablation en cours…' : 'Lancer l’ablation'}
          </button>
          <span className="text-[10px] text-on-surface-variant">
            Bloquant et lent sur CPU : {arms.length} bras × {runs} run(s) × benchmark, plusieurs appels LLM par question.
          </span>
        </div>
      </section>

      {/* Résultats */}
      {mutation.isPending && (
        <div className="flex flex-col items-center justify-center h-40 space-y-3">
          <div className="w-12 h-1 bg-primary/20 relative overflow-hidden">
            <div className="absolute inset-0 bg-primary animate-progress-fast" />
          </div>
          <span className="font-headline text-[10px] uppercase tracking-widest text-primary animate-pulse">
            Évaluation des bras…
          </span>
        </div>
      )}

      {report && baseArm && !mutation.isPending && (
        <section className="space-y-4">
          <div className="flex items-end justify-between flex-wrap gap-2">
            <h3 className="font-headline text-xs uppercase tracking-widest text-on-surface-variant">
              Résultats · {report.benchmarkSize} questions · deltas vs « {baseArm.label} »
            </h3>
            <button
              onClick={() => downloadCsv(report)}
              className="px-3 py-1.5 border border-outline-variant/30 text-on-surface-variant font-headline text-[10px] uppercase tracking-widest hover:border-primary hover:text-on-surface transition-colors"
            >
              Exporter CSV
            </button>
          </div>

          {noRetrieval && (
            <p className="text-[11px] text-secondary bg-secondary/10 border border-secondary/20 px-3 py-2">
              Métriques de retrieval (Hit@k/MRR/Recall) non calculées : aucune question du benchmark n'est annotée
              du champ <code>expectedSources</code>. Ajoutez-le pour mesurer la qualité de récupération.
            </p>
          )}

          <div className="overflow-x-auto border border-outline-variant/10">
            <table className="w-full text-xs">
              <thead>
                <tr className="bg-surface-container-low text-on-surface-variant">
                  <th className="text-left font-label text-[10px] uppercase tracking-widest px-3 py-2">Bras</th>
                  {METRICS.map(m => (
                    <th key={m.key} className="text-right font-label text-[10px] uppercase tracking-widest px-3 py-2" title={m.help}>
                      {m.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {report.arms.map((arm, i) => (
                  <tr key={i} className="border-t border-outline-variant/10 hover:bg-surface-container-high/30">
                    <td className="px-3 py-2">
                      <span className="text-on-surface font-medium">{arm.label}</span>
                      <span className="block text-[10px] text-on-surface-variant">
                        {arm.model}{arm.useRag ? '' : ' · sans RAG'}
                      </span>
                    </td>
                    {METRICS.map(m => {
                      const std = fmtStd(m, arm);
                      const sig = isSignificant(m, baseArm, arm);
                      return (
                        <td key={m.key} className="px-3 py-2 text-right tabular-nums">
                          <span className="text-on-surface">{m.format(arm)}</span>
                          {std && <span className="text-[9px] text-on-surface-variant ml-1">{std}</span>}
                          {i > 0 && (
                            <span
                              className={`block text-[10px] ${sig ? deltaColor(m.value(arm) - m.value(baseArm), m.higherIsBetter) : 'text-on-surface-variant/50'}`}
                              title={sig ? '' : 'Delta dans le bruit (≤ σ combiné) — non significatif'}
                            >
                              {!sig && fmtDelta(m, baseArm, arm) ? '≈ ' : ''}{fmtDelta(m, baseArm, arm)}
                            </span>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {report.arms.some(a => a.runs > 1)
            ? <p className="text-[10px] text-on-surface-variant">
                Valeurs = moyenne sur {report.arms[0]?.runs} répétitions (±σ). Un delta grisé « ≈ » est dans le bruit
                (≤ σ combiné) : non significatif.
              </p>
            : <p className="text-[10px] text-on-surface-variant">
                1 seule répétition : les deltas ne sont pas testés contre le bruit. Augmentez « répétitions » (≥3) pour fiabiliser.
              </p>}

          {/* Graphiques */}
          <AblationCharts arms={report.arms} />

          {/* Modules effectivement déclenchés (validation) */}
          <div className="space-y-2">
            <h4 className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
              Modules effectivement déclenchés (validation que l'option a pris effet)
            </h4>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
              {report.arms.map((arm, i) => {
                const fired = Object.entries(arm.appliedCounts || {});
                return (
                  <div key={i} className="bg-surface-container-low border border-outline-variant/10 p-3">
                    <p className="text-[11px] text-on-surface font-medium mb-1">{arm.label}</p>
                    {fired.length === 0 ? (
                      <p className="text-[10px] text-on-surface-variant">aucun module (génération directe)</p>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {fired.map(([k, n]) => (
                          <span key={k} className="px-1.5 py-0.5 bg-primary/10 text-primary text-[10px] border border-primary/20">
                            {MODULE_NAME[k] ?? k} ×{n}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>

          {/* Légende des métriques */}
          <details className="bg-surface-container-low border border-outline-variant/10 p-4">
            <summary className="cursor-pointer font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
              Comment lire ces métriques ?
            </summary>
            <ul className="mt-3 space-y-1.5">
              {METRICS.map(m => (
                <li key={m.key} className="text-[11px] text-on-surface-variant">
                  <strong className="text-on-surface">{m.label}</strong> — {m.help}{' '}
                  <span className="text-on-surface-variant/70">({m.higherIsBetter ? 'plus haut = mieux' : 'plus bas = mieux'})</span>
                </li>
              ))}
              <li className="text-[11px] text-on-surface-variant pt-1">
                Les deltas en <span className="text-primary">vert</span> indiquent une amélioration, en{' '}
                <span className="text-error">rouge</span> une dégradation, par rapport au premier bras.
              </li>
            </ul>
          </details>
        </section>
      )}
    </div>
  );
};

export default Optimization;
