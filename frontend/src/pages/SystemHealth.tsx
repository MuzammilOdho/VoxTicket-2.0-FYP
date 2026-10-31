import { useSystemHealth } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { QueryState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { fmtTime } from '../components/KpiCard';

export function SystemHealth() {
  const q = useSystemHealth();
  return (
    <div>
      <PageHeader title="System health" sub="Java backend, PostgreSQL/pgvector, voice worker, LLM providers and embedding services. Auto-refreshes every 30s. This is a point-in-time snapshot — no health history is stored." />
      <QueryState query={q}>
        {(h) => (
          <div className="rounded-[24px] border border-line bg-paper p-5 shadow-card">
            <p className="border-b border-line pb-4 text-[14px] text-ink">
              Overall status: <StatusBadge value={h.status} />
            </p>
            <div className="divide-y divide-line-soft">
              {h.components.map((c) => (
                <div key={c.name} className="py-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm font-medium text-ink">{c.name}</div>
                    <StatusBadge value={c.status} />
                  </div>
                  {c.detail && <div className="mt-1 whitespace-pre-wrap text-xs text-ink-mute">{c.detail}</div>}
                  <div className="mt-1 text-[11px] text-ink-mute">Checked {fmtTime(c.checkedAt)}</div>
                </div>
              ))}
            </div>
          </div>
        )}
      </QueryState>
    </div>
  );
}
