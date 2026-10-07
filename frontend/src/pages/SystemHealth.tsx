import { useSystemHealth } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
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
              <span className="text-sm text-ink-dim">Overall status:</span>
              <StatusBadge value={h.status} />
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
              {h.components.map((c) => (
                <div key={c.name} className="rounded-none border border-line bg-raised p-4 shadow-none">
                  <div className="flex items-center justify-between">
                    <div className="text-sm font-semibold text-ink">{c.name}</div>
                    <StatusBadge value={c.status} />
                  </div>
                  {c.detail && <div className="mt-2 whitespace-pre-wrap text-xs text-ink-mute">{c.detail}</div>}
                  <div className="mt-2 text-[11px] text-ink-mute">Checked {fmtTime(c.checkedAt)}</div>
                </div>
              ))}
            </div>
          </div>
        )}
      </QueryState>
    </div>
  );
}
