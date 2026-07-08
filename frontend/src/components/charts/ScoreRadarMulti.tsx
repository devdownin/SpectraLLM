import type { FC } from 'react';
import {
  RadarChart, Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis,
  ResponsiveContainer, Tooltip, Legend,
} from 'recharts';
import type { ModelComparisonEntry } from '../../types/api';

const CATEGORY_LABEL: Record<string, string> = {
  qa:             'Q/R',
  summary:        'Summary',
  classification: 'Classif.',
  negative:       'Negative',
};

/** Palette stable pour distinguer chaque modèle sur le radar superposé. */
export const MODEL_COLORS = ['#8ff5ff', '#c4a7ff', '#ffd166', '#7bdcb5', '#ff8fab', '#ffa94d'];

interface Props {
  categories: string[];
  models: ModelComparisonEntry[];
}

const CustomTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-surface-container border border-primary/20 px-3 py-2 text-[11px] font-label space-y-1">
      <p className="text-on-surface-variant uppercase tracking-widest">{label}</p>
      {payload.map((p: any) => (
        <p key={p.dataKey} className="font-bold" style={{ color: p.color }}>
          {p.dataKey}: {Number(p.value).toFixed(2)} / 10
        </p>
      ))}
    </div>
  );
};

/**
 * Radar superposé : une série par modèle comparé, pour visualiser d'un coup
 * d'œil les forces et faiblesses relatives par catégorie.
 */
const ScoreRadarMulti: FC<Props> = ({ categories, models }) => {
  if (categories.length === 0 || models.length === 0) return null;

  const data = categories.map(cat => {
    const row: Record<string, number | string> = { subject: CATEGORY_LABEL[cat] ?? cat };
    models.forEach(m => {
      const v = m.scoresByCategory[cat];
      if (v !== undefined) row[m.modelName] = v;
    });
    return row;
  });

  return (
    <ResponsiveContainer width="100%" height="100%">
      <RadarChart data={data} margin={{ top: 8, right: 24, bottom: 8, left: 24 }}>
        <PolarGrid stroke="rgba(255,255,255,0.06)" />
        <PolarAngleAxis
          dataKey="subject"
          tick={{ fill: 'rgba(222,229,255,0.55)', fontSize: 10, fontFamily: 'Space Grotesk' }}
        />
        <PolarRadiusAxis domain={[0, 10]} tick={false} axisLine={false} />
        {models.map((m, i) => (
          <Radar
            key={m.evalId}
            name={m.modelName}
            dataKey={m.modelName}
            stroke={MODEL_COLORS[i % MODEL_COLORS.length]}
            fill={MODEL_COLORS[i % MODEL_COLORS.length]}
            fillOpacity={0.08}
            strokeWidth={1.5}
          />
        ))}
        <Legend wrapperStyle={{ fontSize: 10, fontFamily: 'Space Grotesk', textTransform: 'uppercase', letterSpacing: '0.1em' }} />
        <Tooltip content={<CustomTooltip />} />
      </RadarChart>
    </ResponsiveContainer>
  );
};

export default ScoreRadarMulti;
