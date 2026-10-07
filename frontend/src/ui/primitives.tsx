/** VoxTicket UI primitives — Linear "midnight precision instrument".
 *  6px buttons/inputs, 12px cards, 9999px pills, 4px badges.
 *  Acid lime (#e4f222) is the ONLY chromatic action element. */
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

/* ---------- layout ---------- */

export function Card({ children, className = '', pad = true, glass = false }: {
  children: ReactNode;
  className?: string;
  pad?: boolean;
  glass?: boolean;
}) {
  return (
    <div
      className={`${glass ? 'g-border' : 'border border-line bg-raised'} rounded-xl ${
        pad ? 'p-6' : ''
      } ${className}`}
    >
      {children}
    </div>
  );
}

export function SectionTitle({ children, right }: { children: ReactNode; right?: ReactNode }) {
  return (
    <div className="mb-4 flex items-center justify-between">
      <h2 className="text-[11px] font-medium uppercase tracking-[0.14em] text-ink-mute">{children}</h2>
      {right}
    </div>
  );
}

/* ---------- buttons ---------- */

type BtnVariant = 'primary' | 'ghost' | 'danger' | 'light' | 'pill';

const btnBase =
  'inline-flex items-center justify-center gap-2 px-4 py-2 text-[13px] font-medium tracking-[-0.011em] transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-40';

const btnStyles: Record<BtnVariant, string> = {
  // The one chromatic button in the system.
  primary:
    'rounded-md bg-signal text-[#08090a] hover:brightness-110 hover:shadow-[0_8px_28px_-8px_rgba(228,242,34,0.45)] active:brightness-95 disabled:hover:bg-signal disabled:hover:shadow-none',
  light:
    'rounded-md bg-ink text-[#08090a] hover:bg-white disabled:hover:bg-ink',
  ghost:
    'rounded-md border border-line bg-transparent text-ink-dim hover:border-smoke hover:bg-white/[0.04] hover:text-ink',
  danger:
    'rounded-md border border-bad/50 text-bad hover:bg-bad/10',
  // White pill — nav CTA.
  pill:
    'rounded-full bg-white text-[#08090a] hover:bg-bone active:bg-mist',
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
      className={`${btnBase} ${btnStyles[variant]} ${className}`}
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
    <Link to={to} className={`${btnBase} ${btnStyles[variant]} ${className}`}>
      {children}
    </Link>
  );
}

/* ---------- badges / status ---------- */

export function Badge({ tone = 'mute', children, pulse }: {
  tone?: 'mute' | 'signal' | 'ok' | 'warn' | 'bad' | 'info' | 'violet';
  children: ReactNode;
  pulse?: boolean;
}) {
  const tones: Record<string, string> = {
    mute: 'bg-white/[0.05] text-ink-mute',
    signal: 'bg-signal/10 text-signal',
    ok: 'bg-ok/10 text-ok',
    warn: 'bg-warn/10 text-warn',
    bad: 'bg-bad/10 text-bad',
    info: 'bg-info/10 text-info',
    violet: 'bg-iris-violet/15 text-lavender',
  };
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded px-1.5 py-0.5 text-[12px] font-normal ${tones[tone]}`}
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
    ? 'border-signal/60 bg-signal/10 text-signal'
    : 'border-line bg-white/[0.03] text-ink-dim hover:border-smoke hover:text-ink';
  const inner = (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-[12px] transition-colors ${cls} ${className}`}>
      {children}
    </span>
  );
  return onClick ? <button onClick={onClick}>{inner}</button> : inner;
}

/* ---------- inputs ---------- */

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[11px] font-medium uppercase tracking-[0.14em] text-ink-mute">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-ink-mute">{hint}</span>}
    </label>
  );
}

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={`w-full rounded-md border border-line bg-white/[0.02] px-3.5 py-3 text-[14px] text-ink placeholder:text-ink-mute/70 outline-none transition-colors focus:border-ink-dim ${props.className ?? ''}`}
    />
  );
}

export function Select(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      {...props}
      className={`rounded-md border border-line bg-raised px-2.5 py-1.5 text-sm text-ink outline-none transition-colors focus:border-ink-dim ${props.className ?? ''}`}
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
    <div className="rounded-xl border border-dashed border-line px-4 py-10 text-center">
      <div className="text-sm font-medium text-ink-dim">{title}</div>
      {body && <div className="mt-1 text-xs text-ink-mute">{body}</div>}
    </div>
  );
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center gap-3 px-4 py-10 text-sm text-ink-mute">
      <span className="vq-pulse inline-block h-2 w-2 rounded-full bg-signal" />
      {label}…
    </div>
  );
}

export function ErrorBox({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="rounded-xl border border-bad/40 bg-bad/5 px-4 py-6 text-center">
      <div className="text-sm font-medium text-bad">Something went wrong</div>
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
  return <hr className="border-line-soft" />;
}

export function Mono({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <span className={`font-mono ${className}`}>{children}</span>;
}

/** Kicker — small uppercase section label, Linear style. */
export function Kicker({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`text-[11px] font-medium uppercase tracking-[0.14em] text-ink-mute ${className}`}>
      {children}
    </div>
  );
}
