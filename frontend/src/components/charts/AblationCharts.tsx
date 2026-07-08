import type { FC } from 'react';
import {
  BarChart, Bar, Cell, ErrorBar, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ScatterChart, Scatter, ZAxis,
} from 'recharts';
import type { AblationArmReport } from '../../types/api';

const PRIMARY = '#8ff5ff';
const POSITIVE = '#7ee787';
const NEGATIVE = '#ff7b9c';
const AXIS = 'rgba(222,229,255,0.55)';
const GRID = 'rgba(255,255,255,0.06)';

const r = (n: number, d = 2) => Number(n.toFixed(d));

interface Props {
  arms: AblationArmReport[];
}

const TooltipBox = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-surface-container border border-primary/20 px-3 py-2 text-[11px] font-label space-y-0.5">
      <p className="text-on-surface-variant uppercase tracking-widest">{label ?? payload[0]?.payload?.name}</p>
      {payload.map((p: any, i: number) => (
        <p key={i} className="text-on-surface">{p.name}: <span className="text-primary font-bold">{p.value}</span></p>
      ))}
    </div>
  );
};

const AblationCharts: FC<Props> = ({ arms }) => {
  if (!arms?.length) return null;

  const hasRetrieval = arms.some(a => a.retrieval.evaluatedQuestions > 0);

  // Barres : exactitude /10 avec barres d'erreur (±écart-type).
  const scoreData = arms.map(a => ({
    name: a.label,
    score: r(a.quality.avgScore),
    err: r(a.stdDev?.avgScore ?? 0),
  }));

  // Nuage coût/qualité : x = tokens de contexte, y = exactitude. Frontière de Pareto visible.
  const scatterData = arms.map(a => ({
    name: a.label,
    x: Math.round(a.avgContextTokens),
    y: r(a.quality.avgScore),
  }));

  // Waterfall : gain marginal d'exactitude vs bras précédent.
  const waterfall = arms.map((a, i) => ({
    name: a.label,
    delta: i === 0 ? 0 : r(a.quality.avgScore - arms[i - 1].quality.avgScore),
  }));

  return (
    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
      {/* Exactitude par bras (±σ) */}
      <div className="bg-surface-container-low border border-outline-variant/10 p-3">
        <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant mb-2">
          Exactitude /10 par bras (±σ)
        </p>
        <div className="h-56">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={scoreData} margin={{ top: 8, right: 8, bottom: 28, left: -16 }}>
              <CartesianGrid stroke={GRID} vertical={false} />
              <XAxis dataKey="name" tick={{ fill: AXIS, fontSize: 10 }} interval={0} angle={-20} textAnchor="end" height={40} />
              <YAxis domain={[0, 10]} tick={{ fill: AXIS, fontSize: 10 }} />
              <Tooltip content={<TooltipBox />} cursor={{ fill: 'rgba(255,255,255,0.03)' }} />
              <Bar dataKey="score" fill={PRIMARY} fillOpacity={0.7}>
                <ErrorBar dataKey="err" width={4} strokeWidth={1.2} stroke="#dee5ff" direction="y" />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Coût / qualité (Pareto) */}
      <div className="bg-surface-container-low border border-outline-variant/10 p-3">
        <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant mb-2">
          Coût / qualité — tokens de contexte vs exactitude
        </p>
        <div className="h-56">
          <ResponsiveContainer width="100%" height="100%">
            <ScatterChart margin={{ top: 8, right: 12, bottom: 28, left: -16 }}>
              <CartesianGrid stroke={GRID} />
              <XAxis type="number" dataKey="x" name="tokens"
                tick={{ fill: AXIS, fontSize: 10 }}
                label={{ value: 'tokens contexte', position: 'insideBottom', offset: -14, fill: AXIS, fontSize: 10 }} />
              <YAxis type="number" dataKey="y" name="exactitude" domain={[0, 10]} tick={{ fill: AXIS, fontSize: 10 }} />
              <ZAxis range={[80, 80]} />
              <Tooltip content={<TooltipBox />} cursor={{ strokeDasharray: '3 3' }} />
              <Scatter data={scatterData} fill={PRIMARY} />
            </ScatterChart>
          </ResponsiveContainer>
        </div>
        {!hasRetrieval && (
          <p className="text-[10px] text-on-surface-variant mt-1">
            (les bras sans RAG ont 0 token de contexte)
          </p>
        )}
      </div>

      {/* Gain marginal (waterfall) */}
      <div className="bg-surface-container-low border border-outline-variant/10 p-3">
        <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant mb-2">
          Gain marginal d'exactitude (vs bras précédent)
        </p>
        <div className="h-56">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={waterfall} margin={{ top: 8, right: 8, bottom: 28, left: -16 }}>
              <CartesianGrid stroke={GRID} vertical={false} />
              <XAxis dataKey="name" tick={{ fill: AXIS, fontSize: 10 }} interval={0} angle={-20} textAnchor="end" height={40} />
              <YAxis tick={{ fill: AXIS, fontSize: 10 }} />
              <Tooltip content={<TooltipBox />} cursor={{ fill: 'rgba(255,255,255,0.03)' }} />
              <Bar dataKey="delta">
                {waterfall.map((d, i) => (
                  <Cell key={i} fill={d.delta >= 0 ? POSITIVE : NEGATIVE} fillOpacity={0.8} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  );
};

export default AblationCharts;
