import type { FC } from 'react';
import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { qualityBenchmarkApi } from '../services/api';
import { toast } from 'sonner';

/**
 * Boucle « comparatif → qualité mesurée ».
 *
 * Le Model Hub note le fit matériel (score llmfit) ; la qualité réelle se mesure sur VOTRE
 * corpus. Après installation + activation d'un modèle, ce bloc propose de lancer directement
 * le benchmark qualité du nouveau modèle (candidate) contre le précédent (baseline) — pour
 * choisir sur des chiffres mesurés, pas seulement sur la compatibilité matérielle.
 *
 * Le benchmark est lent (plusieurs appels LLM par question, ×2 modèles) : on pilote le job
 * asynchrone côté serveur et on sonde son avancement.
 */

interface Report {
  model: string;
  /** Model that scored the answers — equal to `model` when self-judged. */
  judgeModel?: string | null;
  total: number;
  answerableCount: number;
  unanswerableCount: number;
  avgScore: number;
  hallucinationRate: number;
  refusalAccuracy: number;
}

interface CompareJob {
  jobId: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  baseline: string;
  candidate: string;
  currentStep?: string;
  baselineReport?: Report | null;
  candidateReport?: Report | null;
  error?: string | null;
}

const pct = (v: number) => `${Math.round(v * 100)}%`;

const MetricRow: FC<{ label: string; baseline: number; candidate: number; higherIsBetter: boolean; format: 'score' | 'pct' }> = ({ label, baseline, candidate, higherIsBetter, format }) => {
  const fmt = (v: number) => (format === 'pct' ? pct(v) : `${v.toFixed(2)}/10`);
  const delta = candidate - baseline;
  const neutral = Math.abs(delta) < (format === 'pct' ? 0.005 : 0.01);
  const good = higherIsBetter ? delta > 0 : delta < 0;
  const deltaCls = neutral ? 'text-outline' : good ? 'text-primary' : 'text-error';
  const deltaText = `${delta > 0 ? '+' : ''}${format === 'pct' ? pct(delta) : delta.toFixed(2)}`;
  return (
    <div className="grid grid-cols-[1fr_auto_auto_auto] gap-3 items-center px-4 py-2 text-xs">
      <span className="text-outline">{label}</span>
      <span className="font-mono tabular-nums text-right w-16">{fmt(baseline)}</span>
      <span className="font-mono tabular-nums text-right w-16 font-bold">{fmt(candidate)}</span>
      <span className={`text-right w-16 font-bold ${deltaCls}`}>{deltaText}</span>
    </div>
  );
};

const QualityBenchmarkCta: FC<{ candidate: string; baseline: string; onDismiss: () => void }> = ({ candidate, baseline, onDismiss }) => {
  const [jobId, setJobId] = useState<string | null>(null);

  const start = useMutation({
    mutationFn: () => qualityBenchmarkApi.compareAsync(baseline, candidate),
    onSuccess: (res) => setJobId(res.data.jobId),
    onError: (error: any) => {
      const conflict = error?.response?.status === 409;
      toast.error(conflict ? 'A quality benchmark is already running' : 'Failed to start the quality benchmark', {
        description: error?.response?.data?.error ?? error?.response?.data?.detail ?? error.message,
      });
    },
  });

  const { data: job } = useQuery<CompareJob>({
    queryKey: ['quality-compare', jobId],
    queryFn: async () => (await qualityBenchmarkApi.getCompareJob(jobId!)).data,
    enabled: !!jobId,
    refetchInterval: (query) => {
      const s = query.state.data?.status;
      return s === 'COMPLETED' || s === 'FAILED' ? false : 3000;
    },
  });

  const running = start.isPending || (!!job && (job.status === 'PENDING' || job.status === 'RUNNING'));
  const done = job?.status === 'COMPLETED' && job.baselineReport && job.candidateReport;
  const failed = job?.status === 'FAILED';

  const scoreDelta = done ? job!.candidateReport!.avgScore - job!.baselineReport!.avgScore : 0;
  const hallucDelta = done ? job!.candidateReport!.hallucinationRate - job!.baselineReport!.hallucinationRate : 0;
  // Un juge neutre unique note les deux modèles → scores équitablement comparables.
  // Sinon chaque modèle s'est auto-jugé (rapports antérieurs sans le champ : indéterminé).
  const judge = done ? job!.candidateReport!.judgeModel ?? null : null;
  const neutralJudge = !!judge
    && judge === job!.baselineReport!.judgeModel
    && judge !== job!.candidateReport!.model
    && judge !== job!.baselineReport!.model;

  return (
    <section className="bg-secondary/5 border border-secondary/30 p-4 space-y-3">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <span className="material-symbols-outlined text-secondary text-sm mt-0.5 shrink-0">experiment</span>
          <div className="space-y-1">
            <p className="text-[11px] font-label font-bold uppercase tracking-widest text-secondary">
              Measure quality on your corpus
            </p>
            <p className="text-[10px] text-on-surface-variant leading-relaxed max-w-2xl">
              The Model Hub score rates <strong>hardware fit</strong>. To choose on real numbers, run the held-out
              quality benchmark of the newly activated model
              (<code className="font-mono bg-surface-container px-1">{candidate}</code>) against the one it replaced
              (<code className="font-mono bg-surface-container px-1">{baseline}</code>).
            </p>
          </div>
        </div>
        <button
          onClick={onDismiss}
          aria-label="Dismiss quality benchmark suggestion"
          className="shrink-0 text-outline hover:text-on-surface transition-colors"
        >
          <span className="material-symbols-outlined text-sm">close</span>
        </button>
      </div>

      {!jobId && !start.isPending && (
        <button
          onClick={() => start.mutate()}
          className="flex items-center gap-2 px-4 py-2 bg-secondary text-on-secondary font-headline uppercase tracking-widest text-[11px] font-black hover:bg-secondary/90 transition-colors"
        >
          <span className="material-symbols-outlined text-sm">play_arrow</span>
          Run quality benchmark
        </button>
      )}

      {running && (
        <div className="flex items-center gap-2 text-[11px] text-secondary">
          <span className="material-symbols-outlined text-sm animate-spin">sync</span>
          <span>{job?.currentStep ?? 'Starting…'}</span>
          <span className="text-outline">(held-out benchmark, slow on CPU)</span>
        </div>
      )}

      {failed && (
        <p className="text-xs text-error bg-error/10 px-3 py-2">
          Benchmark failed: {job?.error ?? 'unknown error'}
        </p>
      )}

      {done && (
        <div className="bg-surface-container-lowest border border-outline-variant/10">
          <div className="grid grid-cols-[1fr_auto_auto_auto] gap-3 items-center px-4 py-2 text-[10px] uppercase tracking-widest text-outline font-bold border-b border-outline-variant/10">
            <span>Metric</span>
            <span className="text-right w-16 truncate" title={baseline}>{baseline}</span>
            <span className="text-right w-16 truncate" title={candidate}>{candidate}</span>
            <span className="text-right w-16">Δ</span>
          </div>
          <MetricRow label="Accuracy (answerable)" baseline={job!.baselineReport!.avgScore} candidate={job!.candidateReport!.avgScore} higherIsBetter format="score" />
          <MetricRow label="Hallucination rate" baseline={job!.baselineReport!.hallucinationRate} candidate={job!.candidateReport!.hallucinationRate} higherIsBetter={false} format="pct" />
          <MetricRow label="Refusal accuracy" baseline={job!.baselineReport!.refusalAccuracy} candidate={job!.candidateReport!.refusalAccuracy} higherIsBetter format="pct" />
          <div className="px-4 py-2 border-t border-outline-variant/10 text-[11px] text-on-surface-variant">
            {scoreDelta > 0.05
              ? <>✓ <strong className="text-primary">{candidate}</strong> is more accurate on your corpus (+{scoreDelta.toFixed(2)}/10){hallucDelta < -0.01 ? ` and hallucinates less` : ''}.</>
              : scoreDelta < -0.05
                ? <>✗ <strong className="text-error">{candidate}</strong> scores lower on accuracy ({scoreDelta.toFixed(2)}/10) — the previous model may be a safer choice.</>
                : <>≈ Both models score similarly on accuracy; decide on hallucination rate, speed and hardware fit.</>}
          </div>
          {judge && (
            <div className="px-4 py-1.5 border-t border-outline-variant/10 text-[10px] text-outline">
              {neutralJudge
                ? <>Scored by neutral judge <code className="font-mono">{judge}</code> — scores are directly comparable.</>
                : <>Each model scored its own answers (self-judged) — set <code className="font-mono">SPECTRA_EVALUATION_JUDGE_MODEL</code> for a fairer comparison.</>}
            </div>
          )}
        </div>
      )}
    </section>
  );
};

export default QualityBenchmarkCta;
