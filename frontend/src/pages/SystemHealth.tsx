import { useSystemHealth } from '../api/hooks';
import { PageHeader } from '../components/Layout';
import { QueryState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { fmtTime } from '../components/KpiCard';

export function SystemHealth() {
  const q = useSystemHealth();
  return (
    <div>
      <PageHeader title="System health" sub="Java backend, PostgreSQL/pgvector, voice worker, LLM providers and embedding services. Auto-refreshes every 30s." />
      <QueryState query={q}>
        {(h) => (
          <div>
            <div className="mb-4 flex items-center gap-2">
              <span className="text-sm text-slate-600">Overall status:</span>
              <StatusBadge value={h.status} />
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
              {h.components.map((c) => (
                <div key={c.name} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
                  <div className="flex items-center justify-between">
                    <div className="text-sm font-semibold text-slate-800">{c.name}</div>
                    <StatusBadge value={c.status} />
                  </div>
                  {c.detail && <div className="mt-2 whitespace-pre-wrap text-xs text-slate-500">{c.detail}</div>}
                  <div className="mt-2 text-[11px] text-slate-400">Checked {fmtTime(c.checkedAt)}</div>
                </div>
              ))}
            </div>
          </div>
        )}
      </QueryState>
    </div>
  );
}
