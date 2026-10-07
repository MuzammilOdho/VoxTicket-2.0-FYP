import { Badge } from '../ui/primitives';

function tone(value: string): 'ok' | 'bad' | 'warn' | 'mute' | 'signal' | 'info' {
  const v = value.toUpperCase();
  if (['UP', 'COMPLETED', 'SUCCESS', 'OK', 'RESOLVED', 'CHAT'].includes(v)) return 'ok';
  if (['DOWN', 'FAILED', 'ABORTED', 'ERROR', 'MODEL_ERROR'].includes(v)) return 'bad';
  if (['DEGRADED', 'ACTIVE', 'CLARIFICATION', 'VOICE', 'PHONE'].includes(v)) return 'warn';
  if (['UNKNOWN', 'EXPIRED', 'PENDING'].includes(v)) return 'mute';
  if (['ESCALATED', 'SAFETY_BLOCKED', 'BLOCKED'].includes(v)) return 'signal';
  return 'info';
}

export function StatusBadge({ value, label }: { value: string | null | undefined; label?: string }) {
  const v = value ?? 'unknown';
  return <Badge tone={tone(v)}>{label ?? v}</Badge>;
}

export function BoolBadge({ value, trueLabel = 'yes', falseLabel = 'no' }: { value: boolean | null | undefined; trueLabel?: string; falseLabel?: string }) {
  if (value === null || value === undefined) return <span className="text-xs text-ink-mute">n/a</span>;
  return <Badge tone={value ? 'ok' : 'mute'}>{value ? trueLabel : falseLabel}</Badge>;
}
