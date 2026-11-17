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

/* Warm palette from admin/tokens.ts: black first, then warm grays.
   One accent on a chart, never a rainbow. */
import { CHART_COLORS, SHADCN_TOKENS } from '../admin/tokens';

const COLORS = [...CHART_COLORS];

const AXIS = { fontSize: 12, fill: SHADCN_TOKENS.midGray, fontFamily: 'var(--font-sans)' };

export interface NameValue {
  name: string;
  value: number;
}

export function recordToNameValue(rec: Record<string, number> | undefined): NameValue[] {
  if (!rec) return [];
  return Object.entries(rec).map(([name, value]) => ({ name, value }));
}

function ChartShell({ title, height = 180, children }: { title?: string; height?: number; children: React.ReactElement }) {
  // Charts were 240–300px tall and filled the screen. 180px is enough to
  // read a distribution at a glance; the exact values live in the tables
  // next to the charts. Each chart is its own card so a grid of charts
  // reads as a set of panels, not one wall of ink.
  return (
    <div className="rounded-xl border border-line bg-paper p-5 shadow-card">
      {title && <div className="mb-2 text-[14px] font-medium text-ink">{title}</div>}
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          {children}
        </ResponsiveContainer>
      </div>
    </div>
  );
}

const tooltipStyle = {
  backgroundColor: SHADCN_TOKENS.paper,
  border: `1px solid ${SHADCN_TOKENS.hairline}`,
  borderRadius: 10,
  boxShadow: 'rgba(28, 40, 64, 0.1) 0px 2px 3px -2px, rgba(28, 40, 64, 0.04) 0px 4px 6px -2px',
  fontSize: 12,
  color: SHADCN_TOKENS.ink,
};

export function BarChartCard({ title, data, height }: { title?: string; data: NameValue[]; height?: number }) {
  return (
    <ChartShell title={title} height={height}>
      <BarChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke={SHADCN_TOKENS.hairline} />
        <XAxis dataKey="name" tick={AXIS} interval={0} angle={-18} dy={8} height={48} />
        <YAxis tick={AXIS} />
        <Tooltip contentStyle={tooltipStyle} />
        <Bar dataKey="value">
          {data.map((d, i) => (
            <Cell key={i} fill={d.value === Math.max(...data.map((x) => x.value)) ? SHADCN_TOKENS.accent : SHADCN_TOKENS.midGray} />
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
        <Pie data={data} dataKey="value" nameKey="name" innerRadius="55%" outerRadius="85%" paddingAngle={2} label={{ fontSize: 11, fill: SHADCN_TOKENS.ink }} stroke={SHADCN_TOKENS.paper}>
          {data.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip contentStyle={tooltipStyle} />
        <Legend wrapperStyle={{ fontSize: 12, color: SHADCN_TOKENS.midGray }} />
      </PieChart>
    </ChartShell>
  );
}
