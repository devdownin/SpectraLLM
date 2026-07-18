import type { FC } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, Cell, ResponsiveContainer,
} from 'recharts';

interface Props {
  byCategory: Record<string, number>;
  color?: string;
}

const CustomTooltip = ({ active, payload }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-surface-container border border-primary/20 px-3 py-2 text-[11px] font-label">
      <p className="text-on-surface-variant uppercase tracking-widest">{payload[0].payload.name}</p>
      <p className="text-primary font-bold">{payload[0].value} paires</p>
    </div>
  );
};

const CategoryBar: FC<Props> = ({ byCategory, color = '#6673f0' }) => {
  const entries = Object.entries(byCategory).filter(([, n]) => n > 0);
  if (entries.length === 0) return null;

  const data = entries
    .sort(([, a], [, b]) => b - a)
    .map(([name, value]) => ({ name, value }));

  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, bottom: 4, left: 8 }}>
        <CartesianGrid horizontal={false} strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" />
        <XAxis
          type="number"
          tick={{ fill: 'rgba(222,229,255,0.4)', fontSize: 10, fontFamily: 'Space Grotesk' }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          type="category"
          dataKey="name"
          width={80}
          tick={{ fill: 'rgba(222,229,255,0.55)', fontSize: 10, fontFamily: 'Space Grotesk' }}
          axisLine={false}
          tickLine={false}
        />
        <Tooltip content={<CustomTooltip />} cursor={{ fill: 'rgba(143,245,255,0.04)' }} />
        <Bar dataKey="value" radius={[0, 2, 2, 0]} isAnimationActive={false}>
          {data.map((entry, i) => (
            <Cell
              key={entry.name}
              fill={color}
              fillOpacity={1 - i * 0.12}
            />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
};

export default CategoryBar;
