/** shadcn/ui tokens as TypeScript values — replaces the Signal mirror.
 *
 * These MUST match the values in `admin/shadcn.css` (the CSS file is the
 * visual source of truth; this file exists only for code that cannot read
 * CSS variables, i.e. Recharts chart colours in `components/Charts.tsx`
 * and the voice pipeline strip in `components/Card.tsx`).
 * If a colour changes, change it in both places in the same delivery.
 * Contrast, measured: ink #0a0a0a on white 19.80:1, mid-gray #737373
 * on white 4.74:1; the CSS --color-ink-mute is #6f6f6f (two steps
 * darker) so muted text also passes on canvas (4.67:1). Chart axis
 * text sits on white cards and keeps #737373.
 * on white 4.74:1, ember #e7000b on white 4.77:1 (all WCAG AA pass for
 * normal text). Ember on a red tint FAILS (4.36:1 at 5%, 4.01:1 at
 * 10%), so error badges use white text on solid ember (4.77:1) —
 * never ember text on a tint.
 */
export const SHADCN_TOKENS = {
  canvas: '#f5f5f5',
  paper: '#ffffff',
  surfaceAlt: '#fafafa',
  ink: '#0a0a0a',
  inkSoft: '#171717',
  midGray: '#737373',
  hairline: '#e5e5e5',
  ember: '#e7000b',
  accent: '#2545d3',
  accentDim: '#1d37ad',
  ok: '#15803d',
  warn: '#b45309',
} as const;

/** Donut palette: indigo first, then grays. The first segment — the
 * largest category in every donut on these pages — carries the accent;
 * the rest stay gray so the chart reads as one highlight, not a
 * rainbow. Bar charts use the same rule per-bar (see Charts.tsx). */
export const CHART_COLORS = [
  SHADCN_TOKENS.accent,
  SHADCN_TOKENS.midGray,
  '#a3a3a3',
  '#d4d4d4',
  SHADCN_TOKENS.inkSoft,
] as const;
