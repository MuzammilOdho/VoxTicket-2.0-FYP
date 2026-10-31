import { useState } from 'react';
import { useAiRag } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { QueryState } from '../components/States';
import { FilterBar, TextInput } from '../components/Filters';
import { Button } from '../components/Button';
import { KpiCard, fmtInt, fmtPct } from '../components/KpiCard';
import { Latency } from '../components/Latency';

export function AiRag() {
  const [draft, setDraft] = useState({ from: '', to: '' });
  const [applied, setApplied] = useState({ from: '', to: '' });
  const q = useAiRag(applied.from || undefined, applied.to || undefined);
  return (
    <div>
      <PageHeader title="RAG" sub="Knowledge retrieval performance: cache, latency and similarity distribution." />
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
            <div className="mb-6 grid grid-cols-2 gap-6 rounded-[24px] border border-line bg-paper p-5 shadow-card md:grid-cols-3">
              <KpiCard title="Searches" value={fmtInt(r.searches)} />
              <KpiCard title="Cache hit rate" value={fmtPct(r.cacheHitRate)} accent="green" />
              <KpiCard title="Mean latency" value={<Latency ms={r.meanMs} />} accent="sky" />
              <KpiCard title="Similarity p50" value={r.similarityP50 !== null ? r.similarityP50.toFixed(3) : 'n/a'} />
              <KpiCard title="Similarity p95" value={r.similarityP95 !== null ? r.similarityP95.toFixed(3) : 'n/a'} />
              <KpiCard title="Retrieval quality" value={r.similarityP50 !== null && r.similarityP50 >= 0.35 ? 'healthy' : 'check'} accent={r.similarityP50 !== null && r.similarityP50 >= 0.35 ? 'green' : 'amber'} sub="p50 vs 0.35 threshold" />
            </div>
            <p className="rounded-[24px] border border-line bg-paper p-5 text-sm text-ink-mute shadow-card">
              Similarity percentiles describe the cosine-similarity distribution of retrieved knowledge chunks.
              Low p50 values suggest the knowledge base or the similarity threshold needs attention.
            </p>
          </div>
        )}
      </QueryState>
    </div>
  );
}
