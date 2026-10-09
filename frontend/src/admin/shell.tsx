/** Admin operations console shell — Linear-style sidebar + content. */
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
  return `flex items-center gap-2 rounded-md px-3 py-[7px] text-[13px] transition-colors ${
    isActive
      ? 'bg-white/[0.07] text-white'
      : 'text-fog hover:bg-white/[0.03] hover:text-white'
  }`;
}

function ActiveDot({ isActive }: { isActive: boolean }) {
  return (
    <span
      className={`h-1.5 w-1.5 rounded-full transition-colors ${isActive ? 'bg-signal' : 'bg-transparent'}`}
    />
  );
}

export function AdminShell() {
  const navigate = useNavigate();
  let lastGroup = '';
  return (
    <div className="flex min-h-screen bg-void text-white">
      <aside className="flex w-60 shrink-0 flex-col border-r border-line/70 bg-carbon/80">
        <Link to="/admin" className="flex items-center gap-2.5 px-5 pb-5 pt-6">
          <span className="flex h-6 w-6 items-center justify-center rounded-[6px] bg-signal font-mono text-[11px] font-bold text-[#08090a]">
            V
          </span>
          <div>
            <div className="text-[15px] font-medium leading-none tracking-[-0.01em] text-white">VoxTicket</div>
            <div className="mt-1.5 font-mono text-[10px] uppercase tracking-[0.14em] text-ash">Operations</div>
          </div>
        </Link>
        <nav className="flex-1 space-y-0.5 overflow-y-auto px-3 pb-4">
          {NAV.map((n) => {
            const header =
              n.group && n.group !== lastGroup ? (
                <div key={`g-${n.group}`} className="px-3 pb-1.5 pt-5 text-[10px] font-medium uppercase tracking-[0.14em] text-ash">
                  {n.group}
                </div>
              ) : null;
            lastGroup = n.group ?? '';
            return (
              <div key={n.to}>
                {header}
                <NavLink to={n.to} className={navClass} end={n.to === '/admin'}>
                  {({ isActive }) => (
                    <>
                      <ActiveDot isActive={isActive} />
                      {n.label}
                    </>
                  )}
                </NavLink>
              </div>
            );
          })}
        </nav>
        <div className="border-t border-line/70 p-4">
          <div className="mb-3 flex items-center gap-2 px-1">
            <Badge tone="ok" pulse>Live</Badge>
          </div>
          <button
            onClick={() => {
              clearAuth();
              navigate('/admin/login');
            }}
            className="w-full rounded-md border border-line px-3 py-2 text-[13px] text-fog transition-colors hover:border-smoke hover:text-white"
          >
            Log out
          </button>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <div className="mx-auto max-w-[1400px] p-6 md:p-8">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

export function AdminPageHeader({ title, sub, right }: { title: string; sub?: React.ReactNode; right?: React.ReactNode }) {
  return (
    <div className="mb-6 flex items-start justify-between gap-4">
      <div>
        <h1 className="heading-tight text-[24px] font-medium text-white">{title}</h1>
        {sub && <p className="mt-1.5 text-[14px] text-fog">{sub}</p>}
      </div>
      {right}
    </div>
  );
}
