/** Signal figure, Phase 5 (admin rework).
 *
 * A figure is a label, a number, and a note — telemetry readouts,
 * not display type. `accent` colours the value only when the number
 * itself is an outcome: 'red' for an error-adjacent rate (abort rate),
 * 'green'/'amber' for rates judged against a threshold, 'signal'/'sky'
 * for the interaction colour. Default (no accent) stays ink.
 */
export function KpiCard({
  title,
  value,
  sub,
  accent = 'signal',
  className = '',
}: {
  title: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
  accent?: 'signal' | 'green' | 'amber' | 'red' | 'sky';
  className?: string;
}) {
  // Stat block per the reference: label 12px uppercase mid-gray, value
  // 30px/600 with tight tracking. The value takes the accent colour
  // only when the number is itself an outcome (see the header note).
  // NOTE: the default accent is 'signal', but it renders ink — most
  // KPI values are plain numbers, not outcomes, so they stay black
  // text. Only an explicit 'sky' accent renders indigo.
  const accentClass =
    accent === 'red' ? 'text-ember' :
    accent === 'green' ? 'text-ok' :
    accent === 'amber' ? 'text-warn' :
    accent === 'sky' ? 'text-signal' : 'text-ink';
  return (
    <div className={className}>
      <div className="text-[12px] font-medium uppercase tracking-[0.6px] text-ink-mute">{title}</div>
      <div className={`mt-1 text-[30px] font-semibold leading-[1.2] tracking-[-0.025em] ${accentClass}`}>{value}</div>
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
