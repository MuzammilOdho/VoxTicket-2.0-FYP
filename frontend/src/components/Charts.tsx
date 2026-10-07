import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

/* Linear palette: acid lime leads, the rest recede. */
const COLORS = ['#e4f222', '#27a644', '#6366f1', '#02b8cc', '#ffb224', '#eb5757', '#8a8f98', '#62666d'];

const AXIS = { fontSize: 11, fill: '#8a8f98', fontFamily: 'var(--font-mono)' };

export interface NameValue {
  name: string;
  value: number;
}

export function recordToNameValue(rec: Record<string, number> | undefined): NameValue[] {
  if (!rec) return [];
  return Object.entries(rec).map(([name, value]) => ({ name, value }));
}

function ChartShell({ title, height = 240, children }: { title?: string; height?: number; children: React.ReactElement }) {
  return (
    <div className="border border-line bg-raised p-4">
      {title && <div className="mb-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">{title}</div>}
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          {children}
        </ResponsiveContainer>
      </div>
    </div>
  );
}

const tooltipStyle = {
  backgroundColor: '#1d1a18',
  border: '1px solid #3d3a39',
  fontSize: 12,
  color: '#eeeeee',
};

export function BarChartCard({ title, data, height }: { title?: string; data: NameValue[]; height?: number }) {
  return (
    <ChartShell title={title} height={height}>
      <BarChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#23252a" />
        <XAxis dataKey="name" tick={AXIS} interval={0} angle={-18} dy={8} height={48} />
        <YAxis tick={AXIS} />
        <Tooltip contentStyle={tooltipStyle} />
        <Bar dataKey="value">
          {data.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} />
          ))}
        </Bar>
      </BarChart>
    </ChartShell>
  );
}

export function DonutChartCard({ title, data, height }: { title?: string; data: NameValue[]; height?: number }) {
  return (
    <ChartShell title={title} height={height}>
      <PieChart>
        <Pie data={data} dataKey="value" nameKey="name" innerRadius="55%" outerRadius="85%" paddingAngle={2} label={{ fontSize: 11, fill: '#d0d6e0' }} stroke="#0f1011">
          {data.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip contentStyle={tooltipStyle} />
        <Legend wrapperStyle={{ fontSize: 12, color: '#b8b4b1' }} />
      </PieChart>
    </ChartShell>
  );
}
