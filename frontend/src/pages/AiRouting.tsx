import { useAiRouting } from '../api/hooks';
import { PageHeader } from '../components/Layout';
import { QueryState } from '../components/States';
import { KpiCard } from '../components/KpiCard';
import { BarChartCard, DonutChartCard, recordToNameValue } from '../components/Charts';

export function AiRouting() {
  const q = useAiRouting();
  return (
    <div>
      <PageHeader title="AI analytics · Routing" sub="How the router distributes turns across strategies, tiers and reasons." />
      <QueryState query={q}>
        {(r) => (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <KpiCard title="Semantic margin p50" value={r.marginP50 !== null ? r.marginP50.toFixed(4) : 'n/a'} />
              <KpiCard title="Semantic margin p95" value={r.marginP95 !== null ? r.marginP95.toFixed(4) : 'n/a'} />
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <DonutChartCard title="Decisions by strategy" data={recordToNameValue(r.byStrategy)} />
              <BarChartCard title="Decisions by tier" data={recordToNameValue(r.byTier)} />
            </div>
            <BarChartCard title="Decisions by reason" data={recordToNameValue(r.byReason)} height={300} />
          </div>
        )}
      </QueryState>
    </div>
  );
}
