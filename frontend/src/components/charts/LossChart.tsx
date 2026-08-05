import type { FC } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine,
} from 'recharts';

interface LossPoint { epoch: number; loss?: number; evalLoss?: number }

interface Props {
  data: LossPoint[];
  totalEpochs: number;
}

const EVAL_COLOR = '#f0a066';

const CustomTooltip = ({ active, payload }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-surface-container border border-primary/20 px-3 py-2 text-[11px] font-label">
      {/* Époque fractionnaire : 2 décimales, comme la sortie du trainer (« epoch=0.33 »). */}
      <p className="text-on-surface-variant uppercase tracking-widest">
        Epoch {Number(payload[0].payload.epoch).toFixed(2)}
      </p>
      {payload.map((entry: any) => (
        <p key={entry.dataKey} className="font-bold" style={{ color: entry.color }}>
          {entry.dataKey === 'evalLoss' ? 'Eval loss' : 'Loss'} {entry.value.toFixed(4)}
        </p>
      ))}
    </div>
  );
};

const LossChart: FC<Props> = ({ data, totalEpochs }) => {
  if (data.length < 2) {
    return (
      <div className="flex items-center justify-center h-full text-[12px] text-on-surface-variant">
        {data.length === 0 ? 'Waiting for loss data…' : 'Accumulating data…'}
      </div>
    );
  }

  const trainLosses = data.map(d => d.loss).filter((v): v is number => v != null);
  const minLoss = trainLosses.length ? Math.min(...trainLosses) : 0;
  // La courbe de validation n'existe que si valSplit > 0 : ne pas dessiner une série vide.
  const hasEvalLoss = data.some(d => d.evalLoss != null);

  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={data} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" />
        {/* type="number" est indispensable : sur l'axe CATÉGORIEL par défaut, `domain` et
            `tickCount` sont ignorés — l'axe ne listait que les époques déjà collectées et la
            courbe occupait toute la largeur quel que soit l'avancement réel. */}
        <XAxis
          dataKey="epoch"
          type="number"
          domain={[0, Math.max(totalEpochs, 1)]}
          ticks={Array.from({ length: Math.max(totalEpochs, 1) + 1 }, (_, i) => i)}
          allowDecimals={false}
          tick={{ fill: 'rgba(222,229,255,0.4)', fontSize: 10, fontFamily: 'Space Grotesk' }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          tick={{ fill: 'rgba(222,229,255,0.4)', fontSize: 10, fontFamily: 'Space Grotesk' }}
          axisLine={false}
          tickLine={false}
          width={48}
          tickFormatter={(v: number) => v.toFixed(3)}
        />
        <Tooltip content={<CustomTooltip />} cursor={{ stroke: 'rgba(143,245,255,0.15)', strokeWidth: 1 }} />
        {minLoss > 0 && (
          <ReferenceLine
            y={minLoss}
            stroke="rgba(143,245,255,0.25)"
            strokeDasharray="4 4"
            label={{ value: `min ${minLoss.toFixed(3)}`, fill: 'rgba(143,245,255,0.5)', fontSize: 10, fontFamily: 'Space Grotesk' }}
          />
        )}
        <Line
          type="monotone"
          dataKey="loss"
          stroke="#6673f0"
          strokeWidth={2}
          dot={false}
          activeDot={{ r: 4, fill: '#6673f0', strokeWidth: 0 }}
          isAnimationActive={false}
          connectNulls
        />
        {/* Loss de validation : c'est l'écart entre les deux courbes qui signale le
            sur-apprentissage — la loss d'entraînement seule décroît par construction. */}
        {hasEvalLoss && (
          <Line
            type="monotone"
            dataKey="evalLoss"
            stroke={EVAL_COLOR}
            strokeWidth={2}
            strokeDasharray="5 3"
            dot={false}
            activeDot={{ r: 4, fill: EVAL_COLOR, strokeWidth: 0 }}
            isAnimationActive={false}
            connectNulls
          />
        )}
      </LineChart>
    </ResponsiveContainer>
  );
};

export default LossChart;
