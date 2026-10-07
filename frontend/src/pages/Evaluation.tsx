import { useEvaluation } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { KpiCard, fmtPct } from '../components/KpiCard';
import { Latency } from '../components/Latency';
import { BarChartCard, DonutChartCard, recordToNameValue } from '../components/Charts';
import { DataTable } from '../components/Table';

export function Evaluation() {
  const q = useEvaluation();
  return (
    <div>
      <PageHeader title="Evaluation" sub="Outcome quality and latency comparison across tiers, routing and safety." />
      <QueryState query={q}>
        {(e) => (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
              <KpiCard title="Guard block rate" value={fmtPct(e.guardBlockRate)} accent="amber" />
              <KpiCard title="Clarification rate" value={fmtPct(e.clarificationRate)} />
              <KpiCard title="Escalation rate" value={fmtPct(e.escalationRate)} accent="amber" />
              <KpiCard title="Abort rate" value={fmtPct(e.abortRate)} accent="red" />
              <KpiCard title="RAG cache hit rate" value={fmtPct(e.ragCacheHitRate)} accent="green" />
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <DonutChartCard
                title="Routing decisions by reason"
                data={e.routing.map((r) => ({ name: r.reason, value: r.count }))}
              />
              <div className="rounded-none border border-line bg-raised p-4 shadow-none">
                <div className="mb-2 text-sm font-semibold text-ink-dim">Latency by tier</div>
                {e.latencyByTier.length === 0 ? (
                  <EmptyState title="No tier latency data" />
                ) : (
                  <DataTable
                    columns={[
                      { key: 'tier', header: 'Tier' },
                      { key: 'p50', header: 'p50', render: (r) => <Latency ms={r.p50} /> },
                      { key: 'p95', header: 'p95', render: (r) => <Latency ms={r.p95} /> },
                    ]}
                    rows={e.latencyByTier}
                    rowKey={(r) => r.tier}
                  />
                )}
              </div>
            </div>
            <BarChartCard title="Routing reason distribution" data={recordToNameValue(Object.fromEntries(e.routing.map((r) => [r.reason, r.count])))} height={300} />
          </div>
        )}
      </QueryState>
    </div>
  );
}
