import { useState } from 'react';
import { useAiRouting } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { QueryState } from '../components/States';
import { FilterBar, TextInput } from '../components/Filters';
import { Button } from '../components/Button';
import { KpiCard } from '../components/KpiCard';
import { BarChartCard, DonutChartCard, recordToNameValue } from '../components/Charts';

export function AiRouting() {
  const [draft, setDraft] = useState({ from: '', to: '' });
  const [applied, setApplied] = useState({ from: '', to: '' });
  const q = useAiRouting(applied.from || undefined, applied.to || undefined);
  return (
    <div>
      <PageHeader title="Routing" sub="How the router distributes turns across strategies, tiers and reasons." />
      <FilterBar
        dateNote
        chips={(applied.from || applied.to) ? [{ label: `Window: ${applied.from || '…'} → ${applied.to || '…'}`, onRemove: () => { setApplied({ from: '', to: '' }); setDraft({ from: '', to: '' }); } }] : []}
        onReset={() => { setDraft({ from: '', to: '' }); setApplied({ from: '', to: '' }); }}>
        <TextInput label="From" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        <TextInput label="To" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        <Button onClick={() => setApplied(draft)}>Apply</Button>
      </FilterBar>
      <QueryState query={q}>
        {(r) => (
          <div>
            <div className="mb-6 grid grid-cols-2 gap-6 rounded-[24px] border border-line bg-paper p-5 shadow-card">
              <KpiCard title="Semantic margin p50" value={r.marginP50 !== null ? r.marginP50.toFixed(4) : 'n/a'} />
              <KpiCard title="Semantic margin p95" value={r.marginP95 !== null ? r.marginP95.toFixed(4) : 'n/a'} />
            </div>
            <div className="mb-6 grid grid-cols-1 gap-6 md:grid-cols-2">
              <DonutChartCard title="Decisions by strategy" data={recordToNameValue(r.byStrategy)} />
              <BarChartCard title="Decisions by tier" data={recordToNameValue(r.byTier)} />
            </div>
            <div>
              <BarChartCard title="Decisions by reason" data={recordToNameValue(r.byReason)} />
            </div>
          </div>
        )}
      </QueryState>
    </div>
  );
}
