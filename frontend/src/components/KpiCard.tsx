export function KpiCard({
  title,
  value,
  sub,
  accent = 'indigo',
}: {
  title: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
  accent?: 'indigo' | 'green' | 'amber' | 'red' | 'sky';
}) {
  const ring: Record<string, string> = {
    indigo: 'border-t-indigo-500',
    green: 'border-t-emerald-500',
    amber: 'border-t-amber-500',
    red: 'border-t-rose-500',
    sky: 'border-t-sky-500',
  };
  return (
    <div className={`rounded-xl border border-slate-200 border-t-4 bg-white p-4 shadow-sm ${ring[accent]}`}>
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500">{title}</div>
      <div className="mt-1 text-2xl font-semibold text-slate-900">{value}</div>
      {sub !== undefined && <div className="mt-1 text-xs text-slate-500">{sub}</div>}
    </div>
  );
}

export function fmtPct(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return 'n/a';
  return `${(v * 100).toFixed(1)}%`;
}

export function fmtInt(v: number | null | undefined): string {
  if (v === null || v === undefined) return 'n/a';
  return v.toLocaleString();
}

export function fmtUsd(v: number | null | undefined): string {
  if (v === null || v === undefined) return 'n/a';
  return `$${v.toFixed(4)}`;
}

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return 'n/a';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}
