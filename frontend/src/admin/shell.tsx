/** Admin operations console shell — ElevenLabs system (DESIGN-ELEVENLABS.md).
 *
 * Warm eggshell sidebar, taupe active fills, black as the action
 * colour. Sidebar behaviour is preserved: the footer badge reflects
 * the real health endpoint instead of a hardcoded "Live". The active
 * nav item is a taupe pill with ink text — no chromatic accent.
 * Mobile behaviour is kept: a collapsible sidebar with a top bar on
 * small screens. A command palette (see components/CommandPalette)
 * and breadcrumbs (see AdminPageHeader `crumbs`) are part of the shell.
 */
import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { clearAuth } from '../api/client';
import { useSystemHealth } from '../api/hooks';

import { CommandPalette } from '../components/CommandPalette';

// Navigation grouping: grouped by task, not by backend module.
// Challenges is a real tab/route and gets a sidebar entry (it was
// missing — a discoverability defect). Labels are sentence case.
const NAV: { to: string; label: string; group?: string }[] = [
  { to: '/admin', label: 'Overview' },
  { to: '/admin/conversations', label: 'Conversations', group: 'Monitor' },
  { to: '/admin/voice', label: 'Voice', group: 'Monitor' },
  { to: '/admin/health', label: 'Health', group: 'Monitor' },
  { to: '/admin/operations/orders', label: 'Orders', group: 'Records' },
  { to: '/admin/operations/returns', label: 'Returns', group: 'Records' },
  { to: '/admin/operations/refunds', label: 'Refunds', group: 'Records' },
  { to: '/admin/operations/claims', label: 'Claims', group: 'Records' },
  { to: '/admin/operations/verification', label: 'Verification', group: 'Records' },
  { to: '/admin/operations/challenges', label: 'Challenges', group: 'Records' },
  { to: '/admin/operations/escalations', label: 'Escalations', group: 'Records' },
  { to: '/admin/ai/models', label: 'Models', group: 'Intelligence' },
  { to: '/admin/ai/routing', label: 'Routing', group: 'Intelligence' },
  { to: '/admin/ai/rag', label: 'RAG', group: 'Intelligence' },
  { to: '/admin/evaluation', label: 'Evaluation', group: 'Intelligence' },
  { to: '/admin/audit', label: 'Audit', group: 'System' },
];

function navClass({ isActive }: { isActive: boolean }) {
  // Sidebar items: Inter 14px/500, ink text. The active item is a
  // warm taupe pill with ink text.
  // React Router's NavLink adds aria-current="page" automatically.
  return `block rounded-full px-3 py-2.5 text-[14px] transition-colors ${
    isActive ? 'bg-brand-soft font-medium text-brand-ink' : 'text-ink-dim hover:bg-surface hover:text-ink'
  }`;
}

export function AdminShell() {
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const paletteTriggerRef = useRef<HTMLButtonElement | null>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((o) => !o);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  // The footer badge reflects the real health endpoint (shared React
  // Query cache with the Health page, 30s refresh) instead of a
  // hardcoded "Live". While loading it shows nothing decisive.
  const health = useSystemHealth();
  const status = health.data?.status;
  const badgeLabel = status === 'UP' ? 'Live' : status ? status.charAt(0) + status.slice(1).toLowerCase() : '…';
  let lastGroup = '';
  return (
    <div className="flex min-h-screen bg-canvas text-ink">
      {/* Skip link: keyboard and screen-reader users jump past the sidebar. */}
      <a
        href="#admin-main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-[70] focus:bg-brand focus:px-4 focus:py-2 focus:text-white"
      >
        Skip to main content
      </a>
      {/* Polite live region: only alert-worthy health changes are announced. */}
      <div aria-live="polite" className="sr-only">
        {status === 'DEGRADED' || status === 'DOWN' ? `System health is ${status}.` : ''}
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} triggerRef={paletteTriggerRef} />
      {/* Mobile top bar: a collapsible sidebar with a top bar on small screens. */}
      <div className="fixed inset-x-0 top-0 z-40 flex items-center gap-3 border-b border-line bg-paper px-4 py-3 md:hidden">
        <button
          onClick={() => setMenuOpen(!menuOpen)}
          className="min-h-[36px] rounded-full border border-line px-3 py-1.5 text-[13px] font-medium text-ink"
          aria-label={menuOpen ? 'Close menu' : 'Open menu'}
        >
          {menuOpen ? 'Close' : 'Menu'}
        </button>
        <span className="font-display text-[17px] font-semibold tracking-[-0.02em]">VoxTicket</span>
        <span className="font-mono text-[11px] text-ink-mute">Operations</span>
      </div>
      {menuOpen && (
        <button
          aria-label="Close menu"
          onClick={() => setMenuOpen(false)}
          className="fixed inset-0 z-40 bg-black/30 md:hidden"
        />
      )}
      <aside
        className={`fixed inset-y-0 left-0 z-50 w-60 shrink-0 flex-col bg-paper pt-14 md:static md:z-auto md:flex md:pt-0 ${
          menuOpen ? 'flex' : 'hidden md:flex'
        }`}
      >
        <Link to="/admin" className="flex items-center gap-2.5 px-5 pb-5 pt-6" onClick={() => setMenuOpen(false)}>
          <span className="flex h-7 w-7 items-center justify-center rounded-full bg-brand text-[16px] font-semibold text-white">
            V
          </span>
          <div>
            <div className="font-display text-[19px] font-semibold leading-none tracking-[-0.02em] text-ink">VoxTicket</div>
            <div className="mt-1.5 text-[12px] text-ink-mute">Operations console</div>
          </div>
        </Link>
        <div className="px-3 pb-2">
          <button
            ref={paletteTriggerRef}
            onClick={() => setPaletteOpen(true)}
            className="flex w-full items-center justify-between rounded-full bg-surface px-3 py-2 text-left text-[14px] text-ink-mute transition-colors hover:text-ink"
          >
            <span>Search pages…</span>
            <span className="rounded-full border border-line bg-paper px-1.5 py-0.5 text-[11px] font-medium text-ink-mute">⌘K</span>
          </button>
        </div>
        <nav aria-label="Admin navigation" className="flex-1 space-y-0.5 overflow-y-auto px-3 pb-4">
          {NAV.map((n) => {
            const header =
              n.group && n.group !== lastGroup ? (
                <div key={`g-${n.group}`} className="px-3 pb-1.5 pt-5 text-[12px] font-medium text-smoke">
                  {n.group}
                </div>
              ) : null;
            lastGroup = n.group ?? '';
            return (
              <div key={n.to}>
                {header}
                <NavLink to={n.to} className={navClass} end={n.to === '/admin'} onClick={() => setMenuOpen(false)}>
                  {n.label}
                </NavLink>
              </div>
            );
          })}
        </nav>
        <div className="border-t border-line p-4">
          <div className="mb-1 px-1 text-[12px] text-ink-mute">System health</div>
          <div className="mb-3 flex items-center gap-2 px-1">
            <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[12px] font-medium ${status === 'DOWN' ? 'bg-ember text-white' : status === 'DEGRADED' ? 'bg-warn/10 text-warn' : 'bg-ok/10 text-ok'}`}>
              {status === 'UP' && <span className="vq-pulse inline-block h-1.5 w-1.5 rounded-full bg-current" />}
              {badgeLabel}
            </span>
          </div>
          <button
            onClick={() => {
              clearAuth();
              navigate('/admin/login');
            }}
            className="w-full min-h-[36px] rounded-full border border-line px-3 py-2 text-[13px] text-ink-mute transition-colors hover:border-cloud-200 hover:text-ink"
          >
            Log out
          </button>
        </div>
      </aside>
      <main id="admin-main" className="min-w-0 flex-1 pt-14 md:pt-0">
        <div className="mx-auto max-w-[1280px] p-6 md:p-8">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

export function AdminPageHeader({ title, sub, right, crumbs }: {
  title: string;
  sub?: React.ReactNode;
  right?: React.ReactNode;
  crumbs?: { label: string; to?: string }[];
}) {
  return (
    <div className="mb-6 flex items-start justify-between gap-4">
      <div>
        {crumbs && crumbs.length > 0 && (
          <nav aria-label="Breadcrumb" className="mb-2 flex flex-wrap items-center gap-1.5 text-[13px] text-ink-mute">
            {crumbs.map((c, i) => (
              <span key={`${c.label}-${i}`} className="flex items-center gap-1.5">
                {i > 0 && <span aria-hidden>›</span>}
                {c.to ? (
                  <Link to={c.to} className="text-brand-ink underline decoration-brand/30 underline-offset-4 hover:decoration-brand">{c.label}</Link>
                ) : (
                  <span aria-current="page" className="text-ink">{c.label}</span>
                )}
              </span>
            ))}
          </nav>
        )}
        <h1 className="font-display text-[32px] font-light leading-[1.13] tracking-[-0.64px] text-ink">{title}</h1>
        {sub && <p className="mt-1.5 text-[14px] text-ink-mute">{sub}</p>}
      </div>
      {right}
    </div>
  );
}
