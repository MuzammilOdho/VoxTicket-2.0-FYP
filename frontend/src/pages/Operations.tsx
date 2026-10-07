import { useState } from 'react';
import { NavLink, useParams } from 'react-router-dom';
import { useOperations } from '../api/hooks';
import { PageHeader } from '../components/Layout';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, TextInput } from '../components/Filters';
import { DataTable } from '../components/Table';

const TABS = [
  { kind: 'orders', label: 'Orders' },
  { kind: 'returns', label: 'Returns' },
  { kind: 'refunds', label: 'Refunds' },
  { kind: 'claims', label: 'Claims' },
  { kind: 'verification', label: 'Verification' },
  { kind: 'challenges', label: 'Challenges' },
  { kind: 'escalations', label: 'Escalations' },
];

export function Operations() {
  const { kind = 'orders' } = useParams();
  const [page, setPage] = useState(0);
  const [q, setQD] = useState('');
  const [appliedQ, setAppliedQ] = useState<string | undefined>(undefined);
  const data = useOperations(kind, { page, size: 20, q: appliedQ });
  const tab = TABS.find((t) => t.kind === kind);

  return (
    <div>
      <PageHeader title={`Operations · ${tab?.label ?? kind}`} sub="Read-only operational records. Business actions are performed by the conversation flows, not here." />
      <div className="mb-4 flex flex-wrap gap-1">
        {TABS.map((t) => (
          <NavLink
            key={t.kind}
            to={`/operations/${t.kind}`}
            className={({ isActive }) =>
              `rounded-lg px-3 py-1.5 text-sm ${isActive ? 'bg-indigo-600 font-medium text-white' : 'bg-white text-slate-600 border border-slate-200 hover:bg-slate-50'}`
            }
          >
            {t.label}
          </NavLink>
        ))}
      </div>
      <FilterBar>
        <TextInput label="Search" placeholder="id / number / customer" value={q} onChange={(e) => setQD(e.target.value)} />
        <button
          onClick={() => { setPage(0); setAppliedQ(q || undefined); }}
          className="rounded-lg bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-indigo-700"
        >
          Apply
        </button>
      </FilterBar>
      <QueryState query={data} emptyTitle={`No ${tab?.label.toLowerCase() ?? 'records'}`}>
        {(p) =>
          p.content.length === 0 ? (
            <EmptyState title={`No ${tab?.label.toLowerCase() ?? 'records'}`} />
          ) : (
            <DataTable
              columns={Object.keys(p.content[0]).map((k) => ({ key: k, header: k }))}
              rows={p.content}
              rowKey={(_, i) => `${kind}-${i}`}
              page={p.page}
              size={p.size}
              totalPages={p.totalPages}
              totalElements={p.totalElements}
              onPage={setPage}
            />
          )
        }
      </QueryState>
    </div>
  );
}
