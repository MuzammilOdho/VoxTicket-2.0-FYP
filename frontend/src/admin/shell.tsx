/** Admin operations console shell: dark technical sidebar + content. */
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { clearAuth } from '../api/client';
import { Badge } from '../ui/primitives';

const NAV: { to: string; label: string; group?: string }[] = [
  { to: '/admin', label: 'Overview' },
  { to: '/admin/conversations', label: 'Conversations' },
  { to: '/admin/operations/orders', label: 'Orders', group: 'Operations' },
  { to: '/admin/operations/returns', label: 'Returns', group: 'Operations' },
  { to: '/admin/operations/refunds', label: 'Refunds', group: 'Operations' },
  { to: '/admin/operations/claims', label: 'Claims', group: 'Operations' },
  { to: '/admin/operations/verification', label: 'Verification', group: 'Operations' },
  { to: '/admin/operations/escalations', label: 'Escalations', group: 'Operations' },
  { to: '/admin/voice', label: 'Voice' },
  { to: '/admin/ai/models', label: 'Models', group: 'AI' },
  { to: '/admin/ai/routing', label: 'Routing', group: 'AI' },
  { to: '/admin/ai/rag', label: 'RAG', group: 'AI' },
  { to: '/admin/evaluation', label: 'Evaluation' },
  { to: '/admin/audit', label: 'Audit' },
  { to: '/admin/health', label: 'Health' },
];

function navClass({ isActive }: { isActive: boolean }) {
  return `block px-3 py-1.5 text-[13px] transition-colors ${
    isActive
      ? 'bg-raised-2 text-ink border-l-2 border-signal'
      : 'text-ink-mute hover:text-ink border-l-2 border-transparent'
  }`;
}

export function AdminShell() {
  const navigate = useNavigate();
  let lastGroup = '';
  return (
    <div className="flex min-h-screen bg-canvas text-ink">
      <aside className="flex w-60 shrink-0 flex-col border-r border-line bg-raised">
        <Link to="/admin" className="flex items-center gap-2.5 px-4 py-4">
          <span className="flex h-6 w-6 items-center justify-center bg-signal font-mono text-xs font-bold text-[#101010]">V</span>
          <div>
            <div className="text-[15px] font-semibold leading-none text-ink">VoxTicket</div>
            <div className="mt-1 font-mono text-[10px] uppercase tracking-wider text-ink-mute">Operations</div>
          </div>
        </Link>
        <nav className="flex-1 space-y-0.5 overflow-y-auto px-2 pb-4">
          {NAV.map((n) => {
            const header =
              n.group && n.group !== lastGroup ? (
                <div key={`g-${n.group}`} className="px-3 pb-1 pt-4 text-[10px] font-semibold uppercase tracking-[0.14em] text-ink-mute">
                  {n.group}
                </div>
              ) : null;
            lastGroup = n.group ?? '';
            return (
              <div key={n.to}>
                {header}
                <NavLink to={n.to} className={navClass} end={n.to === '/admin'}>
                  {n.label}
                </NavLink>
              </div>
            );
          })}
        </nav>
        <div className="border-t border-line p-3">
          <div className="mb-2 flex items-center gap-2 px-1">
            <Badge tone="ok" pulse>Live</Badge>
          </div>
          <button
            onClick={() => {
              clearAuth();
              navigate('/admin/login');
            }}
            className="w-full border border-line px-3 py-1.5 text-sm text-ink-mute transition-colors hover:border-ink-mute hover:text-ink"
          >
            Log out
          </button>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <div className="mx-auto max-w-[1400px] p-6">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

export function AdminPageHeader({ title, sub, right }: { title: string; sub?: React.ReactNode; right?: React.ReactNode }) {
  return (
    <div className="mb-5 flex items-start justify-between gap-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-ink">{title}</h1>
        {sub && <p className="mt-1 text-sm text-ink-mute">{sub}</p>}
      </div>
      {right}
    </div>
  );
}
