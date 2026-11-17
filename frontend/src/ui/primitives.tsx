/** VoxTicket UI primitives — v4 colour system (see `src/index.css`).
 * Shared by the public site, demo and login. Admin pages use the
 * shared components in components/ (Button, Filters, Table, States).
 *
 * v2 consolidation: Button/ButtonLink here are thin mappings onto the
 * single implementation in components/Button.tsx (same recipe, same
 * pill shape), so the two button systems can no longer drift. Filters'
 * TextInput/SelectInput likewise compose Field/TextInput/Select from
 * this file. Do not add new admin components here — add them under
 * components/.
 *
 * Buttons: primary is the brand indigo fill, white text, 9999px pill.
 * Ghost is warm taupe; outline is a hairline stone border on eggshell.
 * Inputs are 4px radius; cards 20px (rounded-xl); badges and kickers
 * are pills. Focus is the 2px brand outline from index.css. */
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { buttonClassName } from '../components/Button';
import type { ButtonVariant } from '../components/Button';

/* ---------- layout ---------- */

export function Card({ children, className = '', pad = true, glass = false, tone = 'paper' }: {
  children: ReactNode;
  className?: string;
  pad?: boolean;
  /** Kept for call-site compatibility; glass never existed in either
   *  redesign — every card is flat or whisper-shadowed. */
  glass?: boolean;
  /** 'paper' — eggshell card with whisper shadow; 'taupe' — flat warm
   *  taupe panel, no border, no shadow (the dominant card pattern). */
  tone?: 'paper' | 'taupe';
}) {
  void glass;
  return (
    <div
      className={`${tone === 'taupe' ? 'bg-surface' : 'border border-line bg-paper shadow-card'} rounded-xl ${
        pad ? 'p-8' : ''
      } ${className}`}
    >
      {children}
    </div>
  );
}

export function SectionTitle({ children, right }: { children: ReactNode; right?: ReactNode }) {
  return (
    <div className="mb-4 flex items-center justify-between">
      <h2 className="text-[12px] font-medium text-smoke">{children}</h2>
      {right}
    </div>
  );
}

/* ---------- buttons ---------- */

type BtnVariant = 'primary' | 'ghost' | 'danger' | 'light' | 'pill';

/** Primitives variants mapped onto the single Button implementation. */
const MAP_VARIANT: Record<BtnVariant, ButtonVariant> = {
  primary: 'primary',
  pill: 'primary',
  ghost: 'ghost',
  light: 'outline',
  danger: 'danger',
};

export function Button({
  children,
  variant = 'ghost',
  onClick,
  disabled,
  type = 'button',
  className = '',
}: {
  children: ReactNode;
  variant?: BtnVariant;
  onClick?: () => void;
  disabled?: boolean;
  type?: 'button' | 'submit';
  className?: string;
}) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={`${buttonClassName(MAP_VARIANT[variant])} gap-2 ${className}`}
    >
      {children}
    </button>
  );
}

export function ButtonLink({ to, children, variant = 'ghost', className = '' }: {
  to: string;
  children: ReactNode;
  variant?: BtnVariant;
  className?: string;
}) {
  return (
    <Link to={to} className={`${buttonClassName(MAP_VARIANT[variant])} gap-2 ${className}`}>
      {children}
    </Link>
  );
}

/* ---------- badges / status ---------- */

export function Badge({ tone = 'mute', children, pulse, title }: {
  tone?: 'mute' | 'signal' | 'ok' | 'warn' | 'bad' | 'info' | 'violet';
  children: ReactNode;
  pulse?: boolean;
  title?: string;
}) {
  const tones: Record<string, string> = {
    mute: 'bg-surface text-graphite',
    signal: 'bg-brand text-white',
    ok: 'bg-ok/10 text-ok',
    warn: 'bg-warn/10 text-warn',
    bad: 'bg-bad/10 text-[#b42318]',
    info: 'bg-brand text-white',
    violet: 'bg-violet text-white',
  };
  return (
    <span
      title={title}
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-medium ${tones[tone]}`}
    >
      {pulse && <span className="vq-pulse inline-block h-1.5 w-1.5 rounded-full bg-current" />}
      {children}
    </span>
  );
}

/** Pill chip — tags, filters, compact triggers. */
export function Pill({ children, active, onClick, className = '' }: {
  children: ReactNode;
  active?: boolean;
  onClick?: () => void;
  className?: string;
}) {
  const cls = active
    ? 'border-brand bg-brand text-white'
    : 'border-line bg-paper text-ink-dim hover:border-stone hover:text-ink';
  const inner = (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-[12px] font-medium transition-colors ${cls} ${className}`}>
      {children}
    </span>
  );
  return onClick ? <button onClick={onClick}>{inner}</button> : inner;
}

/* ---------- inputs ---------- */

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[13px] font-medium text-smoke">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-ink-mute">{hint}</span>}
    </label>
  );
}

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={`w-full rounded-md border border-line bg-paper px-3.5 py-3 text-[14px] text-ink placeholder:text-ash outline-none transition-colors focus:border-brand focus:ring-2 focus:ring-brand/15 ${props.className ?? ''}`}
    />
  );
}

export function Select(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      {...props}
      className={`rounded-md border border-line bg-paper px-2.5 py-2 text-sm text-ink outline-none transition-colors focus:border-brand ${props.className ?? ''}`}
    />
  );
}

/* ---------- data ---------- */

export function KV({ k, v, mono }: { k: string; v: ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-1.5">
      <dt className="text-xs text-ink-mute">{k}</dt>
      <dd className={`text-sm text-ink ${mono ? 'font-mono' : ''}`}>{v}</dd>
    </div>
  );
}

export function Empty({ title, body }: { title: string; body?: string }) {
  return (
    <div className="rounded-xl border border-dashed border-brand/30 bg-brand-soft/50 px-4 py-10 text-center">
      <div className="text-sm font-medium text-ink-dim">{title}</div>
      {body && <div className="mt-1 text-xs text-ink-mute">{body}</div>}
    </div>
  );
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center gap-3 px-4 py-10 text-sm text-ink-mute">
      <span className="vq-pulse inline-block h-2 w-2 rounded-full bg-brand" />
      {label}…
    </div>
  );
}

export function ErrorBox({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="rounded-xl border border-bad/40 bg-bad/5 px-4 py-6 text-center">
      <div className="text-sm font-medium text-[#b42318]">Something went wrong</div>
      <div className="mt-1 font-mono text-xs text-ink-mute">{message}</div>
      {onRetry && (
        <div className="mt-3">
          <Button variant="ghost" onClick={onRetry}>Retry</Button>
        </div>
      )}
    </div>
  );
}

/** TanStack Query state wrapper. */
export function QueryState<T>({ query, children, loadingLabel }: {
  query: { isLoading: boolean; isError: boolean; error: unknown; data: T | undefined; refetch: () => void };
  children: (data: T) => ReactNode;
  loadingLabel?: string;
}) {
  if (query.isLoading) return <Loading label={loadingLabel} />;
  if (query.isError) {
    const msg = query.error instanceof Error ? query.error.message : 'Request failed';
    return <ErrorBox message={msg} onRetry={() => query.refetch()} />;
  }
  if (query.data === undefined) return <Empty title="No data" />;
  return <>{children(query.data)}</>;
}

/* ---------- misc ---------- */

export function Divider() {
  return <hr className="border-line" />;
}

export function Mono({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <span className={`font-mono ${className}`}>{children}</span>;
}

/** Eyebrow pill — a quiet tinted label above a heading,
 *  sentence case, never tracked-out all-caps. Brand-soft tint
 *  with brand-ink text, so section labels carry the brand
 *  without shouting. */
export function Kicker({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`inline-flex items-center rounded-full border border-brand/20 bg-brand-soft px-3 py-1.5 text-[12px] font-medium text-brand-ink ${className}`}>
      {children}
    </div>
  );
}
