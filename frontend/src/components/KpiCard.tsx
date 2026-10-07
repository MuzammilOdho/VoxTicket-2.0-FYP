export function KpiCard({
  title,
  value,
  sub,
  accent = 'signal',
}: {
  title: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
  accent?: 'signal' | 'green' | 'amber' | 'red' | 'sky';
}) {
  const bar: Record<string, string> = {
    signal: 'bg-signal',
    green: 'bg-ok',
    amber: 'bg-warn',
    red: 'bg-bad',
    sky: 'bg-info',
  };
  return (
    <div className="border border-line bg-raised p-4">
      <div className={`mb-2 h-0.5 w-8 ${bar[accent]}`} />
      <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">{title}</div>
      <div className="mt-1 text-2xl font-semibold tracking-tight text-ink">{value}</div>
      {sub !== undefined && <div className="mt-1 text-xs text-ink-mute">{sub}</div>}
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
