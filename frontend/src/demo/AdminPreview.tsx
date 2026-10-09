/**
 * VoxTicket demo — "Preview admin console".
 *
 * A guided, read-only peek at the operator dashboard from inside the demo.
 *
 * Step 1 (not signed in): shows the demo test credentials (admin / admin)
 * with a one-click sign-in. This runs the exact same HTTP Basic handshake
 * as the admin login page (probeLogin + setAuth) — auth is never bypassed.
 *
 * Step 2 (signed in): renders the real <Dashboard /> component inside a
 * modal, with a banner noting the current demo session is live on it and
 * a link out to the full console at /admin.
 *
 * The full 12-page console stays at /admin; this is a curated preview,
 * not a replacement. Nothing here can mutate anything: the admin API
 * is read-only, and the preview surfaces no prompts, chain-of-thought,
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
      setError('Login failed — check the backend is reachable and the admin account exists.');
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
      aria-label="Preview admin console"
    >
      {!authed ? (
        /* ---------------- step 1: test credentials ---------------- */
        <div className="g-border w-full max-w-[520px] rounded-2xl bg-carbon p-8">
          <div className="flex items-start justify-between gap-4">
            <Kicker className="!text-signal">Operator preview</Kicker>
            <button
              onClick={onClose}
              aria-label="Close"
              className="rounded-md p-1 text-ash transition-colors hover:text-white"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
          <h2 className="heading-tight mt-3 text-[22px] font-medium text-white">
            See what the support team sees
          </h2>
          <p className="mt-2.5 text-[14px] leading-relaxed text-fog">
            This is the operator&apos;s view of VoxTicket — live dashboards over the
            same conversations you&apos;re having in the demo. Sign in with the
            test credentials to open a read-only preview.
          </p>

          <div className="mt-5 rounded-xl border border-dashed border-signal/40 bg-signal/[0.04] p-4">
            <div className="flex items-center justify-between py-1.5">
              <span className="text-[13px] text-ash">Username</span>
              <code className="rounded-md border border-line bg-void px-2.5 py-1 font-mono text-[13px] text-signal">
                {DEMO_USERNAME}
              </code>
            </div>
            <div className="flex items-center justify-between py-1.5">
              <span className="text-[13px] text-ash">Password</span>
              <code className="rounded-md border border-line bg-void px-2.5 py-1 font-mono text-[13px] text-signal">
                {DEMO_PASSWORD}
              </code>
            </div>
          </div>

          {error && (
            <div className="mt-4 rounded-md border border-bad/40 bg-bad/[0.07] p-3 text-[13px] text-bad">
              {error}
            </div>
          )}

          <div className="mt-6 flex items-center gap-4">
            <button
              onClick={signInWithTestCredentials}
              disabled={busy}
              className="rounded-md bg-signal px-5 py-2.5 text-[14px] font-medium text-[#08090a] transition-all hover:brightness-110 disabled:opacity-40"
            >
              {busy ? 'Signing in…' : 'Sign in with test credentials'}
            </button>
            <button onClick={onClose} className="text-[13px] text-fog underline underline-offset-4 transition-colors hover:text-white">
              Not now
            </button>
          </div>

          <p className="mt-5 text-[11.5px] leading-relaxed text-ash">
            Demo credentials only — the full console uses the same HTTP Basic
            login as <span className="font-mono">/admin</span>. The preview is
            read-only; nothing here can change orders, customers, or settings.
          </p>
        </div>
      ) : (
        /* ---------------- step 2: dashboard preview ---------------- */
        <div className="g-border flex max-h-[92dvh] w-full max-w-[1100px] flex-col overflow-hidden rounded-2xl bg-void">
          <div className="flex shrink-0 items-start justify-between gap-4 border-b border-line/70 px-6 pb-5 pt-6 md:px-8">
            <div>
              <div className="flex items-center gap-2.5">
                <Kicker className="!text-signal">Operator preview</Kicker>
                <Badge tone="ok">Signed in as admin (demo)</Badge>
              </div>
              <h2 className="heading-tight mt-2.5 text-[22px] font-medium text-white">Dashboard</h2>
            </div>
            <button
              onClick={onClose}
              className="shrink-0 rounded-md border border-line px-3 py-1.5 text-[13px] text-fog transition-colors hover:border-smoke hover:text-white"
            >
              ← Back to demo
            </button>
          </div>

          <div className="min-w-0 flex-1 overflow-y-auto px-6 py-6 md:px-8">
            {sessionId && (
              <div className="mb-5 flex items-center gap-2.5 rounded-xl border border-signal/30 bg-signal/[0.05] px-4 py-3">
                <span className="relative flex h-2 w-2 shrink-0">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-signal opacity-60" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-signal" />
                </span>
                <p className="text-[13px] text-mist">
                  <span className="font-medium text-white">This session is live here.</span>{' '}
                  The turns from your current demo conversation{' '}
                  <span className="font-mono text-[12px] text-ash">{sessionId.slice(0, 18)}…</span>{' '}
                  appear on this dashboard as they happen.
                </p>
              </div>
            )}
            <Dashboard />
          </div>

          <div className="flex shrink-0 flex-wrap items-center gap-4 border-t border-line/70 px-6 py-4 md:px-8">
            <button
              onClick={onClose}
              className="rounded-md bg-signal px-5 py-2 text-[13px] font-medium text-[#08090a] transition-all hover:brightness-110"
            >
              ← Back to demo
            </button>
            <Link to="/admin" className="text-[13px] text-fog underline underline-offset-4 transition-colors hover:text-white">
              Open full console →
            </Link>
            <span className="ml-auto hidden text-[11.5px] text-ash sm:block">
              Preview build — the full console adds conversations, voice analytics, AI routing, evaluation, audit log, and system health.
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
