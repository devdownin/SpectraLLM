import type { FC } from 'react';
import type { ModelComparisonReport } from '../types/api';
import ScoreRadarMulti, { MODEL_COLORS } from './charts/ScoreRadarMulti';

const CATEGORY_LABEL: Record<string, string> = {
  qa:             'Q&A',
  summary:        'Summary',
  classification: 'Classification',
  negative:       'Negative',
};

/** Affiche un écart signé (gain en vert/primary, perte en rouge, nul atténué). */
function Delta({ value, suffix = '' }: { value: number; suffix?: string }) {
  if (Math.abs(value) < 0.005) {
    return <span className="text-on-surface-variant">—</span>;
  }
  const positive = value > 0;
  return (
    <span className={positive ? 'text-primary font-bold' : 'text-error font-bold'}>
      {positive ? '+' : ''}{value.toFixed(2)}{suffix}
    </span>
  );
}

interface Props {
  report: ModelComparisonReport;
  baselineId: string;
  onBaselineChange: (evalId: string) => void;
  onExport: () => void;
}

/**
 * Vue comparative multi-modèles : classement, gains globaux et par catégorie
 * vs un modèle de référence, attribution documentaire (liens GED) et radar superposé.
 */
const ModelComparisonPanel: FC<Props> = ({ report, baselineId, onBaselineChange, onExport }) => {
  const { categories, models } = report;
  // Couleur stable par modèle (alignée sur le radar superposé).
  const colorOf = (evalId: string) => {
    const idx = models.findIndex(m => m.evalId === evalId);
    return MODEL_COLORS[idx % MODEL_COLORS.length];
  };

  return (
    <div className="space-y-6">
      {/* Overview */}
      <div className="bg-surface-container p-6 space-y-5">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div>
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
              Comparing {models.length} models
            </p>
            <p className="font-headline font-bold text-lg">Gains vs baseline</p>
          </div>
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2">
              <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Baseline</span>
              <select
                value={baselineId}
                onChange={e => onBaselineChange(e.target.value)}
                className="bg-surface-container-low text-xs text-on-surface px-2 py-1 outline-none border border-outline-variant/20 focus:border-primary/50"
                aria-label="Select the baseline model for delta computation"
              >
                {models.map(m => (
                  <option key={m.evalId} value={m.evalId}>{m.modelName}</option>
                ))}
              </select>
            </label>
            <button
              onClick={onExport}
              className="px-3 py-1 text-[11px] font-label uppercase tracking-widest border border-outline-variant/30 text-on-surface-variant hover:bg-surface-container-high hover:text-on-surface transition-colors"
              aria-label="Export comparison as JSON"
            >
              Export JSON
            </button>
          </div>
        </div>

        {/* Ranking table */}
        <div className="overflow-x-auto">
          <table className="w-full text-xs border-collapse">
            <thead>
              <tr className="text-on-surface-variant font-label text-[9px] uppercase tracking-widest border-b border-outline-variant/20">
                <th className="text-left py-2 pr-3 font-medium">#</th>
                <th className="text-left py-2 pr-3 font-medium">Model</th>
                <th className="text-right py-2 px-3 font-medium">Score</th>
                <th className="text-right py-2 px-3 font-medium">Δ vs base</th>
                <th className="text-right py-2 px-3 font-medium" title="Average generation latency per answer">Latency</th>
                <th className="text-right py-2 px-3 font-medium" title="Estimated throughput (~length/4 tokens)">tok/s (est.)</th>
                <th className="text-right py-2 px-3 font-medium">Pairs</th>
                <th className="text-right py-2 px-3 font-medium" title="Documents trained on (GED TRAINED_ON)">Trained docs</th>
                <th className="text-right py-2 pl-3 font-medium" title="Documents evaluated on (GED EVALUATED_ON)">Eval docs</th>
              </tr>
            </thead>
            <tbody>
              {models.map((m, i) => (
                <tr key={m.evalId} className="border-b border-outline-variant/10 last:border-0">
                  <td className="py-2.5 pr-3 font-headline font-bold text-on-surface-variant">{i + 1}</td>
                  <td className="py-2.5 pr-3">
                    <div className="flex items-center gap-2">
                      <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ backgroundColor: colorOf(m.evalId) }} />
                      <span className="font-headline font-bold truncate max-w-[180px]">{m.modelName}</span>
                      {m.baseline && (
                        <span className="font-label text-[8px] uppercase tracking-widest px-1.5 py-0.5 bg-secondary/10 text-secondary border border-secondary/20">
                          baseline
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="py-2.5 px-3 text-right font-headline font-bold">{m.averageScore.toFixed(2)}</td>
                  <td className="py-2.5 px-3 text-right"><Delta value={m.deltaVsBaseline} /></td>
                  <td className="py-2.5 px-3 text-right text-on-surface-variant">
                    {m.avgLatencyMs > 0 ? `${(m.avgLatencyMs / 1000).toFixed(2)}s` : '—'}
                  </td>
                  <td className="py-2.5 px-3 text-right text-on-surface-variant">
                    {m.avgTokensPerSec > 0 ? m.avgTokensPerSec.toFixed(1) : '—'}
                  </td>
                  <td className="py-2.5 px-3 text-right text-on-surface-variant">{m.processed}</td>
                  <td className="py-2.5 px-3 text-right text-on-surface-variant">{m.trainedOnDocs}</td>
                  <td className="py-2.5 pl-3 text-right text-on-surface-variant">{m.evaluatedOnDocs}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
        {/* Per-category delta matrix */}
        <div className="bg-surface-container p-6">
          <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-3">
            Gains by category (vs {report.baselineModel})
          </p>
          <div className="overflow-x-auto">
            <table className="w-full text-xs border-collapse">
              <thead>
                <tr className="text-on-surface-variant font-label text-[9px] uppercase tracking-widest border-b border-outline-variant/20">
                  <th className="text-left py-2 pr-3 font-medium">Model</th>
                  {categories.map(cat => (
                    <th key={cat} className="text-right py-2 px-2 font-medium">{CATEGORY_LABEL[cat] ?? cat}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {models.map(m => (
                  <tr key={m.evalId} className="border-b border-outline-variant/10 last:border-0">
                    <td className="py-2 pr-3">
                      <div className="flex items-center gap-2">
                        <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: colorOf(m.evalId) }} />
                        <span className="truncate max-w-[120px]">{m.modelName}</span>
                      </div>
                    </td>
                    {categories.map(cat => (
                      <td key={cat} className="py-2 px-2 text-right">
                        {m.baseline
                          ? <span className="text-on-surface-variant">—</span>
                          : cat in m.deltaByCategory
                            ? <Delta value={m.deltaByCategory[cat]} />
                            : <span className="text-on-surface-variant/40">n/a</span>}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* Overlaid radar */}
        <div className="bg-surface-container p-6">
          <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">
            Category radar
          </p>
          <div className="h-72">
            {categories.length >= 3 ? (
              <ScoreRadarMulti categories={categories} models={models} />
            ) : (
              <p className="text-xs text-on-surface-variant pt-8 text-center">
                Need at least 3 shared categories to draw a radar.
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ModelComparisonPanel;
