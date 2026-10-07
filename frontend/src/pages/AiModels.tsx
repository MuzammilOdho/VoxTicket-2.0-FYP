import { useAiModels, useAiCost } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { KpiCard, fmtInt, fmtUsd } from '../components/KpiCard';
import { BarChartCard, recordToNameValue } from '../components/Charts';
import { DataTable } from '../components/Table';

export function AiModels() {
  const models = useAiModels();
  const cost = useAiCost();
  return (
    <div>
      <PageHeader title="AI analytics · Models & providers" sub="Tier, provider and model usage plus token consumption and estimated cost." />
      <QueryState query={models}>
        {(m) => (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <KpiCard title="Est. cost" value={fmtUsd(m.costUsd)} />
              <KpiCard title="Tokens (total)" value={fmtInt(Object.values(m.tokensByProvider).reduce((a, b) => a + b, 0))} />
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <BarChartCard title="Selections by tier" data={recordToNameValue(m.byTier)} />
              <BarChartCard title="Selections by provider" data={recordToNameValue(m.byProvider)} />
              <BarChartCard title="Selections by model" data={recordToNameValue(m.byModel)} />
              <BarChartCard title="Tokens by provider" data={recordToNameValue(m.tokensByProvider)} />
            </div>
          </div>
        )}
      </QueryState>

      <h2 className="mb-2 mt-6 text-sm font-semibold text-ink-dim">Cost by model</h2>
      <QueryState query={cost} emptyTitle="No cost data">
        {(c) =>
          c.perModel.length === 0 ? (
            <EmptyState title="No cost data" hint="Cost estimation requires the backend price table." />
          ) : (
            <DataTable
              columns={[
                { key: 'model', header: 'Model' },
                { key: 'tokens', header: 'Tokens', render: (r) => fmtInt(r.tokens) },
                { key: 'costUsd', header: 'Est. cost (USD)', render: (r) => fmtUsd(r.costUsd) },
              ]}
              rows={c.perModel}
              rowKey={(r) => r.model}
            />
          )
        }
      </QueryState>
    </div>
  );
}
