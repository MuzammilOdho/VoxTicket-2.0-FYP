import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { clearAuth } from '../api/client';

const NAV: { to: string; label: string; group?: string }[] = [
  { to: '/', label: 'Dashboard' },
  { to: '/conversations', label: 'Conversations' },
  { to: '/operations/orders', label: 'Orders', group: 'Operations' },
  { to: '/operations/returns', label: 'Returns', group: 'Operations' },
  { to: '/operations/refunds', label: 'Refunds', group: 'Operations' },
  { to: '/operations/claims', label: 'Claims', group: 'Operations' },
  { to: '/operations/verification', label: 'Verification', group: 'Operations' },
  { to: '/operations/escalations', label: 'Escalations', group: 'Operations' },
  { to: '/voice', label: 'Voice Analytics' },
  { to: '/ai/models', label: 'Models/Providers', group: 'AI Analytics' },
  { to: '/ai/routing', label: 'Routing', group: 'AI Analytics' },
  { to: '/ai/rag', label: 'RAG', group: 'AI Analytics' },
  { to: '/evaluation', label: 'Evaluation' },
  { to: '/audit', label: 'Audit Log' },
  { to: '/health', label: 'System Health' },
];

function navClass({ isActive }: { isActive: boolean }) {
  return `block rounded-lg px-3 py-1.5 text-sm ${
    isActive ? 'bg-indigo-100 font-medium text-indigo-900' : 'text-slate-600 hover:bg-slate-100'
  }`;
}

export function Layout() {
  const navigate = useNavigate();
  let lastGroup = '';
  return (
    <div className="flex min-h-screen bg-slate-100">
      <aside className="w-60 shrink-0 border-r border-slate-200 bg-white p-4">
        <Link to="/" className="mb-4 block">
          <div className="text-lg font-bold text-slate-900">VoxTicket</div>
          <div className="text-xs text-slate-500">Admin &amp; Ops Console</div>
        </Link>
        <nav className="space-y-0.5">
          {NAV.map((n) => {
            const header =
              n.group && n.group !== lastGroup ? (
                <div key={`g-${n.group}`} className="pt-3 text-[11px] font-semibold uppercase tracking-wide text-slate-400">
                  {n.group}
                </div>
              ) : null;
            lastGroup = n.group ?? '';
            return (
              <div key={n.to}>
                {header}
                <NavLink to={n.to} className={navClass} end={n.to === '/'}>
                  {n.label}
                </NavLink>
              </div>
            );
          })}
        </nav>
        <button
          onClick={() => {
            clearAuth();
            navigate('/login');
          }}
          className="mt-6 w-full rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50"
        >
          Log out
        </button>
      </aside>
      <main className="min-w-0 flex-1 p-6">
        <Outlet />
      </main>
    </div>
  );
}

export function PageHeader({ title, sub }: { title: string; sub?: React.ReactNode }) {
  return (
    <div className="mb-4">
      <h1 className="text-xl font-bold text-slate-900">{title}</h1>
      {sub && <p className="text-sm text-slate-500">{sub}</p>}
    </div>
  );
}
