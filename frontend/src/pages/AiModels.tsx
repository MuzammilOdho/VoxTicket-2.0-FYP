import { useState } from 'react';
import { useAiModels, useAiCost } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, TextInput } from '../components/Filters';
import { KpiCard, fmtInt, fmtUsd } from '../components/KpiCard';
import { BarChartCard, recordToNameValue } from '../components/Charts';
import { DataTable } from '../components/Table';
import { Button } from '../components/Button';
import { Section } from '../components/Card';

export function AiModels() {
  const [draft, setDraft] = useState({ from: '', to: '' });
  const [applied, setApplied] = useState({ from: '', to: '' });
  const models = useAiModels(applied.from || undefined, applied.to || undefined);
  const cost = useAiCost(applied.from || undefined, applied.to || undefined);
  return (
    <div>
      <PageHeader title="Models and providers" sub="Tier, provider and model usage plus token consumption and estimated cost." />
      <FilterBar
        dateNote
        chips={(applied.from || applied.to) ? [{ label: `Window: ${applied.from || '…'} → ${applied.to || '…'}`, onRemove: () => { setApplied({ from: '', to: '' }); setDraft({ from: '', to: '' }); } }] : []}
        onReset={() => { setDraft({ from: '', to: '' }); setApplied({ from: '', to: '' }); }}>
        <TextInput label="From" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        <TextInput label="To" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        <Button onClick={() => setApplied(draft)}>Apply</Button>
      </FilterBar>
      <QueryState query={models}>
        {(m) => (
          <div>
            <div className="mb-6 grid grid-cols-2 gap-6 rounded-[24px] border border-line bg-paper p-5 shadow-card">
              <KpiCard title="Est. cost" value={fmtUsd(m.costUsd)} />
              <KpiCard title="Tokens (total)" value={fmtInt(Object.values(m.tokensByProvider).reduce((a, b) => a + b, 0))} />
            </div>
            <div className="mb-6 grid grid-cols-1 gap-6 md:grid-cols-2">
              <BarChartCard title="Selections by tier" data={recordToNameValue(m.byTier)} />
              <BarChartCard title="Selections by provider" data={recordToNameValue(m.byProvider)} />
              <BarChartCard title="Selections by model" data={recordToNameValue(m.byModel)} />
              <BarChartCard title="Tokens by provider" data={recordToNameValue(m.tokensByProvider)} />
            </div>
          </div>
        )}
      </QueryState>

      <Section title="Cost by model">
        <QueryState query={cost} emptyTitle="No cost data">
          {(c) =>
            c.perModel.length === 0 ? (
              <EmptyState title="No cost data" hint="Cost estimation requires the backend price table." />
            ) : (
              <DataTable
                columns={[
                  { key: 'model', header: 'Model', sortable: true, sortValue: (r) => r.model },
                  { key: 'tokens', header: 'Tokens', render: (r) => fmtInt(r.tokens), sortable: true, sortValue: (r) => r.tokens },
                  { key: 'costUsd', header: 'Est. cost (USD)', render: (r) => fmtUsd(r.costUsd), sortable: true, sortValue: (r) => r.costUsd },
                ]}
                rows={c.perModel}
                rowKey={(r) => r.model}
              />
            )
          }
        </QueryState>
      </Section>
    </div>
  );
}
