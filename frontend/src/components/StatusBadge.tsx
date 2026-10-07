const base = 'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium';

function tone(value: string): string {
  const v = value.toUpperCase();
  if (['UP', 'COMPLETED', 'SUCCESS', 'OK', 'RESOLVED', 'CHAT'].includes(v)) return 'bg-emerald-100 text-emerald-800';
  if (['DOWN', 'FAILED', 'ABORTED', 'ERROR', 'MODEL_ERROR'].includes(v)) return 'bg-rose-100 text-rose-800';
  if (['DEGRADED', 'ACTIVE', 'CLARIFICATION', 'VOICE', 'PHONE'].includes(v)) return 'bg-amber-100 text-amber-800';
  if (['UNKNOWN', 'EXPIRED', 'PENDING'].includes(v)) return 'bg-slate-200 text-slate-700';
  if (['ESCALATED', 'SAFETY_BLOCKED', 'BLOCKED'].includes(v)) return 'bg-orange-100 text-orange-800';
  return 'bg-indigo-100 text-indigo-800';
}

export function StatusBadge({ value, label }: { value: string | null | undefined; label?: string }) {
  const v = value ?? 'unknown';
  return <span className={`${base} ${tone(v)}`}>{label ?? v}</span>;
}

export function BoolBadge({ value, trueLabel = 'yes', falseLabel = 'no' }: { value: boolean | null | undefined; trueLabel?: string; falseLabel?: string }) {
  if (value === null || value === undefined) return <span className="text-slate-400 text-xs">n/a</span>;
  return (
    <span className={`${base} ${value ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-600'}`}>
      {value ? trueLabel : falseLabel}
    </span>
  );
}
