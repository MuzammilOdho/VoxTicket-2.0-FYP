import { useAiRag } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { QueryState } from '../components/States';
import { KpiCard, fmtInt, fmtPct } from '../components/KpiCard';
import { Latency } from '../components/Latency';

export function AiRag() {
  const q = useAiRag();
  return (
    <div>
      <PageHeader title="AI analytics · RAG" sub="Knowledge retrieval performance: cache, latency and similarity distribution." />
      <QueryState query={q}>
        {(r) => (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
              <KpiCard title="Searches" value={fmtInt(r.searches)} />
              <KpiCard title="Cache hit rate" value={fmtPct(r.cacheHitRate)} accent="green" />
              <KpiCard title="Mean latency" value={<Latency ms={r.meanMs} />} accent="sky" />
              <KpiCard title="Similarity p50" value={r.similarityP50 !== null ? r.similarityP50.toFixed(3) : 'n/a'} />
              <KpiCard title="Similarity p95" value={r.similarityP95 !== null ? r.similarityP95.toFixed(3) : 'n/a'} />
              <KpiCard title="Retrieval quality" value={r.similarityP50 !== null && r.similarityP50 >= 0.35 ? 'healthy' : 'check'} accent={r.similarityP50 !== null && r.similarityP50 >= 0.35 ? 'green' : 'amber'} sub="p50 vs 0.35 threshold" />
            </div>
            <div className="rounded-none border border-line bg-raised p-4 text-sm text-ink-mute shadow-none">
              Similarity percentiles describe the cosine-similarity distribution of retrieved knowledge chunks.
              Low p50 values suggest the knowledge base or the similarity threshold needs attention.
            </div>
          </div>
        )}
      </QueryState>
    </div>
  );
}
