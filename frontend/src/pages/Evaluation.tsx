import { useState } from 'react';
import { useEvaluation } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, TextInput } from '../components/Filters';
import { KpiCard, fmtPct } from '../components/KpiCard';
import { Latency } from '../components/Latency';
import { BarChartCard, recordToNameValue } from '../components/Charts';
import { DataTable } from '../components/Table';
import { Button } from '../components/Button';
import { Section } from '../components/Card';

export function Evaluation() {
  const [draft, setDraft] = useState({ from: '', to: '' });
  const [applied, setApplied] = useState({ from: '', to: '' });
  const q = useEvaluation(applied.from || undefined, applied.to || undefined);
  return (
    <div>
      <PageHeader title="Evaluation" sub="Outcome quality and latency comparison across tiers, routing and safety." />
      <FilterBar
        dateNote
        chips={(applied.from || applied.to) ? [{ label: `Window: ${applied.from || '…'} → ${applied.to || '…'}`, onRemove: () => { setApplied({ from: '', to: '' }); setDraft({ from: '', to: '' }); } }] : []}
        onReset={() => { setDraft({ from: '', to: '' }); setApplied({ from: '', to: '' }); }}>
        <TextInput label="From" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        <TextInput label="To" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        <Button onClick={() => setApplied(draft)}>Apply</Button>
      </FilterBar>
      {applied.from || applied.to ? (
        <p className="mb-6 text-xs text-ink-mute">
          Window {applied.from || '…'} to {applied.to || '…'} (UTC days). Use this to isolate one evaluation
          run: rates, tier latency and routing on this page follow the window.
        </p>
      ) : null}
      <QueryState query={q}>
        {(e) => (
          <div>
            {e.notes && (
              <p className="mb-6 border-l-2 border-signal bg-raised-2 px-4 py-3 text-[13px] text-ink-dim">
                How to read this page: {e.notes}
              </p>
            )}
            <div className="mb-6 grid grid-cols-2 gap-6 rounded-[24px] border border-line bg-paper p-5 shadow-card md:grid-cols-5">
              <KpiCard title="Guard block rate" value={fmtPct(e.guardBlockRate)} accent="amber" />
              <KpiCard title="Clarification rate" value={fmtPct(e.clarificationRate)} />
              <KpiCard title="Escalation rate" value={fmtPct(e.escalationRate)} accent="amber" />
              <KpiCard title="Abort rate" value={fmtPct(e.abortRate)} accent="red" />
              <KpiCard title="RAG cache hit rate" value={fmtPct(e.ragCacheHitRate)} accent="green" />
            </div>
            <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
              <Section title="Voice stage latency by tier" note="p50, from turn metrics.">
                {e.voiceLatencyByTier.length === 0 ? (
                  <EmptyState title="No voice latency data" />
                ) : (
                  <DataTable
                    columns={[
                      { key: 'tier', header: 'Tier' },
                      { key: 'sttP50Ms', header: 'STT p50', render: (r) => <Latency ms={r.sttP50Ms} /> },
                      { key: 'brainP50Ms', header: 'Brain p50', render: (r) => <Latency ms={r.brainP50Ms} /> },
                      { key: 'ttsP50Ms', header: 'TTS p50', render: (r) => <Latency ms={r.ttsP50Ms} /> },
                      { key: 'e2eP50Ms', header: 'End-to-end p50', render: (r) => <Latency ms={r.e2eP50Ms} /> },
                      { key: 'e2eP95Ms', header: 'End-to-end p95', render: (r) => <Latency ms={r.e2eP95Ms} /> },
                      { key: 'turns', header: 'Turns', render: (r) => r.turns },
                    ]}
                    rows={e.voiceLatencyByTier}
                    rowKey={(r) => r.tier}
                  />
                )}
              </Section>
              <Section title="LLM latency by tier">
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
              </Section>
            </div>
            <Section title="Routing reason distribution">
              <BarChartCard data={recordToNameValue(Object.fromEntries(e.routing.map((r) => [r.reason, r.count])))} />
            </Section>
          </div>
        )}
      </QueryState>
    </div>
  );
}
