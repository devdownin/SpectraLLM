import type { FC } from 'react';
import {
  RadarChart, Radar, PolarGrid, PolarAngleAxis,
  ResponsiveContainer, Tooltip,
} from 'recharts';

const CATEGORY_LABEL: Record<string, string> = {
  qa:             'Q/R',
  summary:        'Summary',
  classification: 'Classif.',
  negative:       'Negative',
};

interface Props {
  scoresByCategory: Record<string, number>;
}

const CustomTooltip = ({ active, payload }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-surface-container border border-primary/20 px-3 py-2 text-[11px] font-label">
      <p className="text-on-surface-variant uppercase tracking-widest">{payload[0].payload.subject}</p>
      <p className="text-primary font-bold">{payload[0].value.toFixed(2)} / 10</p>
    </div>
  );
};

const ScoreRadar: FC<Props> = ({ scoresByCategory }) => {
  const entries = Object.entries(scoresByCategory);
  if (entries.length === 0) return null;

  const data = entries.map(([cat, avg]) => ({
    subject: CATEGORY_LABEL[cat] ?? cat,
    score: avg,
    fullMark: 10,
  }));

  return (
    <ResponsiveContainer width="100%" height="100%">
      <RadarChart data={data} margin={{ top: 8, right: 24, bottom: 8, left: 24 }}>
        <PolarGrid stroke="rgba(255,255,255,0.06)" />
        <PolarAngleAxis
          dataKey="subject"
          tick={{ fill: 'rgba(222,229,255,0.55)', fontSize: 10, fontFamily: 'Space Grotesk' }}
        />
        <Radar
          dataKey="score"
          stroke="#6673f0"
          fill="#6673f0"
          fillOpacity={0.12}
          strokeWidth={1.5}
        />
        <Tooltip content={<CustomTooltip />} />
      </RadarChart>
    </ResponsiveContainer>
  );
};

export default ScoreRadar;
