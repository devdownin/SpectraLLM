import type { FC } from 'react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';

// Doit correspondre aux valeurs réelles de IngestedFileEntity.Lifecycle.
const LIFECYCLE_COLORS: Record<string, string> = {
  INGESTED:  '#8ff5ff',
  QUALIFIED: '#b8b3ff',
  TRAINED:   '#4cffb3',
  ARCHIVED:  '#5a6a8a',
};
const FALLBACK_COLORS = ['#8ff5ff', '#b8b3ff', '#4cffb3', '#5a6a8a', '#ff6b8a'];

interface Props {
  byLifecycle: Record<string, number>;
}

const CustomTooltip = ({ active, payload }: any) => {
  if (!active || !payload?.length) return null;
  const { name, value } = payload[0];
  return (
    <div className="bg-surface-container border border-primary/20 px-3 py-2 text-[11px] font-label">
      <p className="text-on-surface-variant uppercase tracking-widest">{name}</p>
      <p className="text-primary font-bold">{value} doc{value > 1 ? 's' : ''}</p>
    </div>
  );
};

const LifecycleDonut: FC<Props> = ({ byLifecycle }) => {
  const entries = Object.entries(byLifecycle).filter(([, n]) => n > 0);
  if (entries.length === 0) return null;

  const data = entries.map(([name, value]) => ({ name, value }));
  const total = data.reduce((s, d) => s + d.value, 0);

  return (
    <div className="relative w-full h-full">
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie
            data={data}
            cx="50%"
            cy="50%"
            innerRadius="55%"
            outerRadius="80%"
            paddingAngle={2}
            dataKey="value"
            isAnimationActive={false}
          >
            {data.map((entry, i) => (
              <Cell
                key={entry.name}
                fill={LIFECYCLE_COLORS[entry.name] ?? FALLBACK_COLORS[i % FALLBACK_COLORS.length]}
                stroke="transparent"
              />
            ))}
          </Pie>
          <Tooltip content={<CustomTooltip />} />
        </PieChart>
      </ResponsiveContainer>
      {/* Center label */}
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
        <span className="font-headline font-bold text-xl">{total}</span>
        <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">docs</span>
      </div>
    </div>
  );
};

export default LifecycleDonut;
