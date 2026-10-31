/** Shared button — Stage 1 (admin redesign).
 *
 * One button for the whole admin console, so primary/ghost/danger styling
 * and interaction states can no longer drift page by page. Variants:
 * - primary: indigo fill, white text (Apply, main actions)
 * - ghost: bordered, ink text (Reset, Export, secondary actions)
 * - danger: red fill, white text (only for destructive-adjacent actions;
 *   the admin has no destructive actions today, so this is reserved)
 * All variants are at least 36 px tall (the reference's button height;
 * WCAG 2.2 AA target size is 24 px). Focus is the 2 px ink outline from
 * shadcn.css. Radius is 18px on every variant — perfect pill geometry.
 */
import type { ButtonHTMLAttributes, ReactNode } from 'react';

type Variant = 'primary' | 'ghost' | 'outline' | 'danger';

const VARIANTS: Record<Variant, string> = {
  // Primary action in the interaction colour (indigo), not black:
  // the one filled button per page should read as the action, and
  // black-on-everything was the "black everywhere" complaint.
  primary: 'bg-signal text-[#fafafa] hover:bg-signal-dim',
  // Ghost: soft gray fill — for use inside white cards, where it reads.
  ghost: 'bg-canvas text-ink hover:bg-line',
  // Outline: hairline border, no fill — for use on the canvas itself,
  // where a ghost fill would disappear into the page background.
  outline: 'border border-line bg-transparent text-ink hover:border-ink-mute',
  // Destructive: ember text only (the reference reserves red for
  // destructive/error contexts; there are no destructive admin actions
  // today, so this variant is reserved, not used).
  danger: 'border border-line bg-transparent text-ember hover:border-ember',
};

export function Button({
  variant = 'primary',
  large = false,
  className = '',
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  large?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      {...rest}
      className={`inline-flex min-h-[36px] items-center justify-center rounded-[18px] px-4 text-[14px] font-medium transition-colors disabled:opacity-40 ${large ? 'min-h-[40px] px-5' : 'py-2'} ${VARIANTS[variant]} ${className}`}
    >
      {children}
    </button>
  );
}
