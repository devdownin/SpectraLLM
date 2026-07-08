import type { FC } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine,
} from 'recharts';

interface LossPoint { epoch: number; loss: number }

interface Props {
  data: LossPoint[];
  totalEpochs: number;
}

const CustomTooltip = ({ active, payload }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-surface-container border border-primary/20 px-3 py-2 text-[11px] font-label">
      <p className="text-on-surface-variant uppercase tracking-widest">Epoch {payload[0].payload.epoch}</p>
      <p className="text-primary font-bold">Loss {payload[0].value.toFixed(4)}</p>
    </div>
  );
};

const LossChart: FC<Props> = ({ data, totalEpochs }) => {
  if (data.length < 2) {
    return (
      <div className="flex items-center justify-center h-full text-[11px] text-outline uppercase tracking-widest italic">
        {data.length === 0 ? 'Waiting for loss data…' : 'Accumulating data…'}
      </div>
    );
  }

  const minLoss = Math.min(...data.map(d => d.loss));

  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={data} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" />
        <XAxis
          dataKey="epoch"
          domain={[1, totalEpochs]}
          tickCount={Math.min(totalEpochs, 6)}
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
          stroke="#8ff5ff"
          strokeWidth={2}
          dot={false}
          activeDot={{ r: 4, fill: '#8ff5ff', strokeWidth: 0 }}
          isAnimationActive={false}
        />
      </LineChart>
    </ResponsiveContainer>
  );
};

export default LossChart;
