/** v4 colour tokens as TypeScript values (see `src/index.css` header
 * for the full palette and contrast notes).
 *
 * These MUST match the values in `src/index.css` (the CSS file is the
 * visual source of truth; this file exists only for code that cannot read
 * CSS variables, i.e. Recharts chart colours in `components/Charts.tsx`
 * and the voice pipeline strip in `components/Card.tsx`).
 * If a colour changes, change it in both places in the same delivery.
 *
 * The interactive accent is brand indigo #4f46e5 (white on it is
 * 6.0:1, WCAG AA). Status colours (ok/warn/ember) appear only on small
 * status badges/dots and error text, never as decoration. Contrast:
 * ink #000000 on eggshell 19.6:1, smoke #777169 on eggshell 4.72:1
 * (both WCAG AA for normal text). Error badges use white text on solid
 * ember — never ember text on a tint.
 */
export const SHADCN_TOKENS = {
  canvas: '#fdfcfc',
  paper: '#fdfcfc',
  surfaceAlt: '#f5f3f1',
  ink: '#000000',
  inkSoft: '#44403b',
  midGray: '#777169',
  hairline: '#ebe8e4',
  ember: '#d92d20',
  accent: '#4f46e5',
  accentDim: '#4338ca',
  brand: '#4f46e5',
  brandStrong: '#4338ca',
  brandSoft: '#eef2ff',
  brandInk: '#3730a3',
  teal: '#0d9488',
  tealDark: '#0f766e',
  violet: '#7c3aed',
  amber: '#d97706',
  ok: '#15803d',
  warn: '#b45309',
} as const;

/** Chart palette: one colour per category, in v4 role order —
 * brand indigo, teal, amber, violet, then warm slate. Donuts and bars
 * use this order so the same category keeps the same colour across
 * pages. No more than five categories are shown in any one chart;
 * a sixth falls back to warm gray. */
export const CHART_COLORS = [
  SHADCN_TOKENS.brand,
  SHADCN_TOKENS.teal,
  SHADCN_TOKENS.amber,
  SHADCN_TOKENS.violet,
  '#78716c',
] as const;
