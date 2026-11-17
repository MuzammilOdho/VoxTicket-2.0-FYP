/**
 * VoxTicket demo — "Preview operations console".
 *
 * A guided, read-only peek at the operator dashboard from inside the demo.
 *
 * Step 1 (not signed in): one-click sign-in with the demo administrator
 * account. This runs the exact same sign-in handshake as the admin login
 * page (probeLogin + setAuth) — auth is never bypassed, and no credential
 * is ever shown on screen.
 *
 * Step 2 (signed in): renders the real <Dashboard /> component inside a
 * modal, with a banner noting the current demo session is live on it and
 * a link out to the full console at /admin.
 *
 * The full console stays at /admin; this is a curated preview, not a
 * replacement. Nothing here can mutate anything: the admin API is
 * read-only, and the preview surfaces no prompts, chain-of-thought,
 * OTP codes, or secrets.
 */

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import { getAuth, probeLogin, setAuth } from '../api/client';
import { Dashboard } from '../pages/Dashboard';
import { Badge, Kicker } from '../ui/primitives';

const DEMO_USERNAME = 'admin';
const DEMO_PASSWORD = 'admin';

export function AdminPreview({
  open,
  onClose,
  sessionId,
}: {
  open: boolean;
  onClose: () => void;
  sessionId?: string | null;
}) {
  const [authed, setAuthed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Re-check auth every time the preview opens — the visitor may have
  // signed into /admin in this tab since the last visit.
  useEffect(() => {
    if (open) {
      setAuthed(getAuth() !== null);
      setError(null);
    }
  }, [open ]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const signInWithTestCredentials = async () => {
    setBusy(true);
    setError(null);
    const basic = btoa(`${DEMO_USERNAME}:${DEMO_PASSWORD}`);
    const ok = await probeLogin(basic);
    setBusy(false);
    if (ok) {
      setAuth(basic);
      setAuthed(true);
    } else {
      setError('Sign-in failed — the service may be unavailable. Try again.');
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm md:p-8"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      role="dialog"
      aria-modal="true"
      aria-label="Preview operations console"
    >
      {!authed ? (
        /* ---------------- step 1: demo-account sign-in ---------------- */
        <div className="w-full max-w-[520px] rounded-xl border border-line bg-paper p-8 shadow-xl">
          <div className="flex items-start justify-between gap-4">
            <Kicker>Operator preview</Kicker>
            <button
              onClick={onClose}
              aria-label="Close"
              className="rounded-full p-1 text-slate-600 transition-colors hover:text-ink"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
          <h2 className="font-display mt-3 text-[22px] font-semibold tracking-[-0.2px] text-ink">
            See what the support team sees
          </h2>
          <p className="mt-2.5 text-[14px] leading-relaxed text-slate-600">
            This is the operator&apos;s view of VoxTicket — live dashboards over the
            same conversations you&apos;re having in the demo. Open a read-only
            preview signed in with the demo administrator account.
          </p>

          {error && (
            <div className="mt-4 rounded-md border border-bad/40 bg-bad/5 p-3 text-[13px] text-[#b42318]">
              {error}
            </div>
          )}

          <div className="mt-6 flex items-center gap-4">
            <button
              onClick={signInWithTestCredentials}
              disabled={busy}
              className="rounded-full bg-brand px-5 py-2.5 text-[14px] font-medium text-white transition-colors hover:bg-brand-strong disabled:opacity-40"
            >
              {busy ? 'Signing in…' : 'Preview the operations console'}
            </button>
            <button onClick={onClose} className="text-[13px] text-slate-600 underline underline-offset-4 transition-colors hover:text-ink">
              Not now
            </button>
          </div>

          <p className="mt-5 text-[11.5px] leading-relaxed text-slate-700">
            The preview is read-only; nothing here can change orders, customers,
            or settings. The full console uses your own administrator sign-in.
          </p>
        </div>
      ) : (
        /* ---------------- step 2: dashboard preview ---------------- */
        <div className="flex max-h-[92dvh] w-full max-w-[1100px] flex-col overflow-hidden rounded-xl border border-line bg-paper shadow-xl">
          <div className="flex shrink-0 items-start justify-between gap-4 border-b border-line px-6 pb-5 pt-6 md:px-8">
            <div>
              <div className="flex items-center gap-2.5">
                <Kicker>Operator preview</Kicker>
                <Badge tone="ok">Signed in with the demo account</Badge>
              </div>
              <h2 className="font-display mt-2.5 text-[22px] font-semibold tracking-[-0.2px] text-ink">Dashboard</h2>
            </div>
            <button
              onClick={onClose}
              className="shrink-0 rounded-full border border-line px-3 py-1.5 text-[13px] text-slate-600 transition-colors hover:border-cloud-200 hover:text-ink"
            >
              ← Back to demo
            </button>
          </div>

          <div className="min-w-0 flex-1 overflow-y-auto px-6 py-6 md:px-8">
            {sessionId && (
              <div className="mb-5 flex items-center gap-2.5 rounded-xl border border-stone bg-surface px-4 py-3">
                <span className="relative flex h-2 w-2 shrink-0">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-teal opacity-60" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-teal" />
                </span>
                <p className="text-[13px] text-ink-dim">
                  <span className="font-medium text-ink">This session is live here.</span>{' '}
                  The turns from your current demo conversation{' '}
                  <span className="font-mono text-[12px] text-slate-700">{sessionId.slice(0, 18)}…</span>{' '}
                  appear on this dashboard as they happen.
                </p>
              </div>
            )}
            <Dashboard />
          </div>

          <div className="flex shrink-0 flex-wrap items-center gap-4 border-t border-line px-6 py-4 md:px-8">
            <button
              onClick={onClose}
              className="rounded-full bg-brand px-5 py-2 text-[13px] font-medium text-white transition-colors hover:bg-brand-strong"
            >
              ← Back to demo
            </button>
            <Link to="/admin" className="text-[13px] text-slate-600 underline underline-offset-4 transition-colors hover:text-ink">
              Open full console →
            </Link>
            <span className="ml-auto hidden text-[11.5px] text-slate-700 sm:block">
              The full console also includes conversations, voice analytics, AI routing, evaluation, audit log, and system health.
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
