/** Admin operations console shell — shadcn theme (replaces Signal).
 *
 * The shell is the only surface that renders inside the `.shadcn`
 * scope (see shadcn.css), so the public site, the demo, and the login
 * page keep the dark theme. Sidebar behaviour from Phase 1 is
 * preserved: the footer badge reflects the real health endpoint
 * instead of a hardcoded "Live". The sidebar marks the active item as
 * an inverted black block (white text) — the filled-button inversion.
 * Mobile behaviour from Phase 3 is kept: a collapsible sidebar with a
 * top bar on small screens.
 * Stage 2 adds a command palette (see components/CommandPalette) and
 * breadcrumbs (see AdminPageHeader `crumbs`).
 */
import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { clearAuth } from '../api/client';
import { useSystemHealth } from '../api/hooks';

import { CommandPalette } from '../components/CommandPalette';
import './shadcn.css';

// Stage 2 navigation grouping (research §7): grouped by task, not by
// backend module. Challenges is a real tab/route and gets a sidebar entry
// (it was missing — a discoverability defect). Labels are sentence case.
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
  // Sidebar items are nested panels (reference nested radius 10px) with
  // a 36px touch height. The active item is an inverted black block
  // (white text) — never a pill or a left rule — at font-medium, not
  // bold: the fill already marks it active, bold on top is noise.
  // React Router's NavLink adds aria-current="page" automatically.
  return `block rounded-[10px] px-3 py-2.5 text-[14px] transition-colors ${
    isActive ? 'border border-line bg-paper font-medium text-signal shadow-card' : 'text-ink-mute hover:bg-canvas hover:text-ink'
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
  // Phase 1 (admin rework): the footer badge reflects the real health
  // endpoint (shared React Query cache with the Health page, 30s refresh)
  // instead of a hardcoded "Live". While loading it shows nothing decisive.
  const health = useSystemHealth();
  const status = health.data?.status;
  const badgeLabel = status === 'UP' ? 'Live' : status ? status.charAt(0) + status.slice(1).toLowerCase() : '…';
  let lastGroup = '';
  return (
    <div className="shadcn flex min-h-screen bg-canvas text-ink">
      {/* Skip link: keyboard and screen-reader users jump past the sidebar. */}
      <a
        href="#admin-main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-[70] focus:bg-ink focus:px-4 focus:py-2 focus:text-white"
      >
        Skip to main content
      </a>
      {/* Polite live region: only alert-worthy health changes are announced. */}
      <div aria-live="polite" className="sr-only">
        {status === 'DEGRADED' || status === 'DOWN' ? `System health is ${status}.` : ''}
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} triggerRef={paletteTriggerRef} />
      {/* Mobile top bar: the old shell had no mobile behaviour. */}
      <div className="fixed inset-x-0 top-0 z-40 flex items-center gap-3 border-b border-line bg-paper px-4 py-3 md:hidden">
        <button
          onClick={() => setMenuOpen(!menuOpen)}
          className="min-h-[36px] rounded-[18px] border border-line px-3 py-1.5 text-[13px] font-medium text-ink"
          aria-label={menuOpen ? 'Close menu' : 'Open menu'}
        >
          {menuOpen ? 'Close' : 'Menu'}
        </button>
        <span className="text-[17px] font-semibold tracking-[-0.025em]">VOXTICKET</span>
        <span className="font-mono text-[11px] uppercase tracking-[0.14em] text-ink-mute">Operations</span>
      </div>
      {menuOpen && (
        <button
          aria-label="Close menu"
          onClick={() => setMenuOpen(false)}
          className="fixed inset-0 z-40 bg-black/30 md:hidden"
        />
      )}
      <aside
        className={`fixed inset-y-0 left-0 z-50 w-60 shrink-0 flex-col bg-raised-2 pt-14 md:static md:z-auto md:flex md:pt-0 ${
          menuOpen ? 'flex' : 'hidden md:flex'
        }`}
      >
        <Link to="/admin" className="flex items-center gap-2.5 px-5 pb-5 pt-6" onClick={() => setMenuOpen(false)}>
          <span className="flex h-7 w-7 items-center justify-center rounded-[6px] bg-ink text-[16px] font-semibold text-white">
            V
          </span>
          <div>
            <div className="text-[19px] font-semibold leading-none tracking-[-0.025em] text-ink">VOXTICKET</div>
            <div className="mt-1.5 text-[12px] text-ink-mute">Operations console</div>
          </div>
        </Link>
        <div className="px-3 pb-2">
          <button
            ref={paletteTriggerRef}
            onClick={() => setPaletteOpen(true)}
            className="flex w-full items-center justify-between rounded-[18px] bg-canvas px-3 py-2 text-left text-[14px] text-ink-mute transition-colors hover:text-ink"
          >
            <span>Search pages…</span>
            <span className="rounded-[6px] border border-line bg-paper px-1.5 py-0.5 text-[11px] font-medium text-ink-mute">⌘K</span>
          </button>
        </div>
        <nav aria-label="Admin navigation" className="flex-1 space-y-0.5 overflow-y-auto px-3 pb-4">
          {NAV.map((n) => {
            const header =
              n.group && n.group !== lastGroup ? (
                <div key={`g-${n.group}`} className="px-3 pb-1.5 pt-5 text-[12px] font-medium uppercase tracking-[0.6px] text-ink-mute">
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
            <span className={`inline-flex items-center gap-1.5 rounded-[18px] px-2.5 py-0.5 text-[12px] font-medium ${status === 'DOWN' ? 'bg-ember text-[#fafafa]' : status === 'DEGRADED' ? 'bg-warn/10 text-warn' : 'bg-ok/10 text-ok'}`}>
              {status === 'UP' && <span className="vq-pulse inline-block h-1.5 w-1.5 rounded-full bg-current" />}
              {badgeLabel}
            </span>
          </div>
          <button
            onClick={() => {
              clearAuth();
              navigate('/admin/login');
            }}
            className="w-full min-h-[36px] rounded-[18px] border border-line px-3 py-2 text-[13px] text-ink-mute transition-colors hover:border-ink-mute hover:text-ink"
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
                  <Link to={c.to} className="hover:text-ink hover:underline hover:underline-offset-4">{c.label}</Link>
                ) : (
                  <span aria-current="page" className="text-ink">{c.label}</span>
                )}
              </span>
            ))}
          </nav>
        )}
        <h1 className="text-[30px] font-semibold leading-[1.2] tracking-[-0.025em] text-ink">{title}</h1>
        {sub && <p className="mt-1.5 text-[14px] text-ink-mute">{sub}</p>}
      </div>
      {right}
    </div>
  );
}
