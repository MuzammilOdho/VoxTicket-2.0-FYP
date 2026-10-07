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

const COLORS = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#0ea5e9', '#8b5cf6', '#ec4899', '#14b8a6'];

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
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      {title && <div className="mb-2 text-sm font-semibold text-slate-700">{title}</div>}
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          {children}
        </ResponsiveContainer>
      </div>
    </div>
  );
}

export function BarChartCard({ title, data, height }: { title?: string; data: NameValue[]; height?: number }) {
  return (
    <ChartShell title={title} height={height}>
      <BarChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="name" tick={{ fontSize: 11 }} interval={0} angle={-18} dy={8} height={48} />
        <YAxis tick={{ fontSize: 11 }} />
        <Tooltip />
        <Bar dataKey="value" radius={[4, 4, 0, 0]}>
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
        <Pie data={data} dataKey="value" nameKey="name" innerRadius="55%" outerRadius="85%" paddingAngle={2} label={{ fontSize: 11 }}>
          {data.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip />
        <Legend wrapperStyle={{ fontSize: 12 }} />
      </PieChart>
    </ChartShell>
  );
}
