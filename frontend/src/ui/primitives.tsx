/** VoxTicket UI primitives: Factory-dark technical surfaces. */
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

/* ---------- layout ---------- */

export function Card({ children, className = '', pad = true }: { children: ReactNode; className?: string; pad?: boolean }) {
  return (
    <div className={`border border-line bg-raised ${pad ? 'p-4' : ''} ${className}`}>
      {children}
    </div>
  );
}

export function SectionTitle({ children, right }: { children: ReactNode; right?: ReactNode }) {
  return (
    <div className="mb-3 flex items-center justify-between">
      <h2 className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">{children}</h2>
      {right}
    </div>
  );
}

/* ---------- buttons ---------- */

type BtnVariant = 'primary' | 'ghost' | 'danger' | 'light';

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
  const base =
    'inline-flex items-center justify-center gap-2 px-4 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-40';
  const styles: Record<BtnVariant, string> = {
    primary: 'bg-signal text-[#101010] hover:bg-[#ff6f38] disabled:hover:bg-signal',
    light: 'bg-ink text-[#101010] hover:bg-white disabled:hover:bg-ink',
    ghost: 'border border-line bg-transparent text-ink hover:border-ink-mute hover:bg-raised-2',
    danger: 'border border-bad/60 text-bad hover:bg-bad/10',
  };
  return (
    <button type={type} onClick={onClick} disabled={disabled} className={`${base} ${styles[variant]} ${className}`}>
      {children}
    </button>
  );
}

export function ButtonLink({ to, children, variant = 'ghost', className = '' }: { to: string; children: ReactNode; variant?: BtnVariant; className?: string }) {
  const base = 'inline-flex items-center justify-center gap-2 px-4 py-2 text-sm font-medium transition-colors';
  const styles: Record<BtnVariant, string> = {
    primary: 'bg-signal text-[#101010] hover:bg-[#ff6f38]',
    light: 'bg-ink text-[#101010] hover:bg-white',
    ghost: 'border border-line bg-transparent text-ink hover:border-ink-mute hover:bg-raised-2',
    danger: 'border border-bad/60 text-bad hover:bg-bad/10',
  };
  return (
    <Link to={to} className={`${base} ${styles[variant]} ${className}`}>
      {children}
    </Link>
  );
}

/* ---------- badges / status ---------- */

export function Badge({ tone = 'mute', children, pulse }: { tone?: 'mute' | 'signal' | 'ok' | 'warn' | 'bad' | 'info'; children: ReactNode; pulse?: boolean }) {
  const tones: Record<string, string> = {
    mute: 'border-line text-ink-mute',
    signal: 'border-signal/60 text-signal',
    ok: 'border-ok/50 text-ok',
    warn: 'border-warn/50 text-warn',
    bad: 'border-bad/60 text-bad',
    info: 'border-info/50 text-info',
  };
  return (
    <span className={`inline-flex items-center gap-1.5 border px-2 py-0.5 font-mono text-[11px] uppercase tracking-wider ${tones[tone]}`}>
      {pulse && <span className="vq-pulse inline-block h-1.5 w-1.5 rounded-full bg-current" />}
      {children}
    </span>
  );
}

/* ---------- inputs ---------- */

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-ink-mute">{hint}</span>}
    </label>
  );
}

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={`w-full border border-line bg-canvas px-3 py-2 text-sm text-ink placeholder:text-ink-mute/60 outline-none focus:border-ink-mute ${props.className ?? ''}`}
    />
  );
}

export function Select(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      {...props}
      className={`border border-line bg-canvas px-2.5 py-1.5 text-sm text-ink outline-none focus:border-ink-mute ${props.className ?? ''}`}
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
    <div className="border border-dashed border-line px-4 py-10 text-center">
      <div className="text-sm font-medium text-ink-dim">{title}</div>
      {body && <div className="mt-1 text-xs text-ink-mute">{body}</div>}
    </div>
  );
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center gap-3 px-4 py-10 text-sm text-ink-mute">
      <span className="vq-pulse inline-block h-2 w-2 bg-signal" />
      {label}…
    </div>
  );
}

export function ErrorBox({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="border border-bad/40 bg-bad/5 px-4 py-6 text-center">
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
