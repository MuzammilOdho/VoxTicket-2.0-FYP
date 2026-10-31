/**
 * Status badges — colour carries meaning here, and only here:
 * - Error states (FAILED, ABORTED, ERROR, BLOCKED, SAFETY_BLOCKED,
 *   DOWN, MODEL_ERROR): solid ember with white text (4.77:1). Ember
 *   on a tint fails WCAG AA (4.36:1), so error badges never use a tint.
 * - Positive terminal states (UP, COMPLETED, SUCCESS, OK, RESOLVED,
 *   VERIFIED): solid green with white text (5.05:1). A table of
 *   completed rows now reads green at a glance instead of gray.
 * - DEGRADED / CLARIFICATION: solid amber with white text (4.90:1).
 * - ESCALATED (needs attention, not an error): solid indigo with
 *   white text (7.24:1) — attention, in the interaction colour.
 * - Channels and other neutral labels (CHAT, VOICE, PHONE, ACTIVE,
 *   in-progress, unknown, pending): indigo tint — they are clickable
 *   context, not outcomes, so they get the interaction colour at
 *   low weight, not a solid outcome colour.
 * Pill geometry: 18px radius, 12px/500, 2px 8px padding, per the
 * reference's badge spec. This file no longer uses primitives Badge
 * (a dark-theme component); admin badges live here.
 */
type BadgeKind = 'error' | 'ok' | 'warn' | 'accent' | 'info' | 'soft' | 'outline';

const CLASSES: Record<BadgeKind, string> = {
  error: 'bg-ember text-[#fafafa]',
  ok: 'bg-ok text-[#fafafa]',
  warn: 'bg-warn text-[#fafafa]',
  accent: 'bg-signal text-[#fafafa]',
  info: 'bg-signal/10 text-signal',
  soft: 'bg-canvas text-ink-soft',
  outline: 'border border-line bg-transparent text-ink',
};

function kind(value: string): BadgeKind {
  const v = value.toUpperCase();
  if (['DOWN', 'FAILED', 'ABORTED', 'ERROR', 'MODEL_ERROR', 'SAFETY_BLOCKED', 'BLOCKED'].includes(v)) return 'error';
  if (['UP', 'COMPLETED', 'SUCCESS', 'OK', 'RESOLVED', 'VERIFIED'].includes(v)) return 'ok';
  if (['DEGRADED', 'CLARIFICATION'].includes(v)) return 'warn';
  if (v === 'ESCALATED') return 'accent';
  if (['CHAT', 'VOICE', 'PHONE', 'ACTIVE'].includes(v)) return 'info';
  return 'soft';
}

function Pill({ kind: k, children }: { kind: BadgeKind; children: React.ReactNode }) {
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-[18px] px-2 py-0.5 text-[12px] font-medium ${CLASSES[k]}`}>
      {children}
    </span>
  );
}

export function StatusBadge({ value, label }: { value: string | null | undefined; label?: string }) {
  const v = value ?? 'unknown';
  return <Pill kind={kind(v)}>{label ?? v}</Pill>;
}

export function BoolBadge({ value, trueLabel = 'yes', falseLabel = 'no' }: { value: boolean | null | undefined; trueLabel?: string; falseLabel?: string }) {
  if (value === null || value === undefined) return <span className="text-xs text-ink-mute">n/a</span>;
  return <Pill kind={value ? 'info' : 'outline'}>{value ? trueLabel : falseLabel}</Pill>;
}
