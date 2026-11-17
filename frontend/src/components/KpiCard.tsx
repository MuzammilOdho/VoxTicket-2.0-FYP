/** KPI figure — v4 colour system (see `src/index.css` header).
 *
 * A figure is a label, a number, and a note — telemetry readouts,
 * not display type. `accent` colours the value only when the number
 * itself is an outcome: 'red' for an error-adjacent rate (abort rate),
 * 'green'/'amber' for rates judged against a threshold. Default
 * (no accent) stays ink. `dot` adds one small coloured dot before
 * the label — the per-KPI colour that lets five numbers be scanned
 * at a glance (brand, green, teal, amber, violet in that order on
 * the Dashboard). Values stay ink; only the dot is coloured.
 */
export function KpiCard({
  title,
  value,
  sub,
  accent = 'signal',
  dot,
  className = '',
}: {
  title: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
  accent?: 'signal' | 'green' | 'amber' | 'red' | 'sky';
  /** Tailwind bg class for the small dot before the label. */
  dot?: string;
  className?: string;
}) {
  // Stat block: label 12px smoke, value 30px/300 whisper-weight
  // with tight tracking. The value takes the accent colour only when the number
  // is itself an outcome (see the header note). NOTE: the default
  // accent is 'signal', but it renders ink — most KPI values are plain
  // numbers, not outcomes, so they stay ink text. Only an explicit
  // 'sky' accent renders ink (no chromatic accent in this system).
  const accentClass =
    accent === 'red' ? 'text-ember' :
    accent === 'green' ? 'text-ok' :
    accent === 'amber' ? 'text-warn' :
    accent === 'sky' ? 'text-ink' : 'text-ink';
  return (
    <div className={className}>
      <div className="flex items-center gap-1.5 text-[12px] font-medium text-ink-mute">
        {dot && <span className={`inline-block h-2 w-2 shrink-0 rounded-full ${dot}`} aria-hidden />}
        {title}
      </div>
      <div className={`mt-1 text-[30px] font-light leading-[1.2] tracking-[-0.02em] ${accentClass}`}>{value}</div>
      {sub !== undefined && <div className="mt-1 text-[13px] text-ink-mute">{sub}</div>}
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
