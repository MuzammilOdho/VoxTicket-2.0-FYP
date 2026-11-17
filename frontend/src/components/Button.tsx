/** Shared button — v4 colour system (see `src/index.css` header).
 *
 * One button implementation for the whole app. `ui/primitives.tsx`
 * re-exports this (its Button/ButtonLink map onto these variants),
 * so primary/ghost/danger styling can no longer drift between the
 * site, demo and admin console. Variants:
 * - primary: brand indigo fill, white text (6.0:1) — the only
 *   filled button. Pill shape (9999px) is the system's most
 *   recognizable component. Hover is brand-strong.
 * - ghost: warm taupe fill — for use inside eggshell cards, where it
 *   reads as a second surface level.
 * - outline: hairline stone border, no fill — for use on the canvas
 *   itself, where a ghost fill would disappear into the page.
 * - danger: red text only (there are no destructive admin actions
 *   today, so this variant is reserved, not used).
 * All variants are at least 36 px tall (WCAG 2.2 AA target size is
 * 24 px). Focus is the 2 px brand outline from index.css.
 */
import type { ButtonHTMLAttributes, ReactNode } from 'react';

export type ButtonVariant = 'primary' | 'ghost' | 'outline' | 'danger';

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-brand text-white hover:bg-brand-strong',
  ghost: 'bg-surface text-ink hover:bg-stone',
  outline: 'border border-line bg-paper text-ink hover:border-stone',
  danger: 'border border-line bg-paper text-[#b42318] hover:border-bad',
};

/** Shared class recipe, used by the <button> below and by
 * `ui/primitives.tsx` ButtonLink so links and buttons match. */
export function buttonClassName(variant: ButtonVariant = 'primary', large = false, extra = ''): string {
  return `inline-flex min-h-[36px] items-center justify-center rounded-full px-5 text-[14px] font-medium transition-colors disabled:opacity-40 ${large ? 'min-h-[40px] px-6' : 'py-2'} ${VARIANTS[variant]} ${extra}`;
}

export function Button({
  variant = 'primary',
  large = false,
  className = '',
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  large?: boolean;
  children: ReactNode;
}) {
  return (
    <button {...rest} className={buttonClassName(variant, large, className)}>
      {children}
    </button>
  );
}
