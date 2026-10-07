import { Link } from 'react-router-dom';
import { useSummary } from '../api/hooks';
import { PageHeader } from '../components/Layout';
import { EmptyState, QueryState } from '../components/States';
import { KpiCard, fmtInt, fmtPct, fmtUsd } from '../components/KpiCard';
import { Latency } from '../components/Latency';
import { StatusBadge } from '../components/StatusBadge';
import { BarChartCard, DonutChartCard, recordToNameValue } from '../components/Charts';
import { DataTable } from '../components/Table';
import { fmtTime } from '../components/KpiCard';

export function Dashboard() {
  const q = useSummary();
  return (
    <div>
      <PageHeader title="Dashboard" sub="Operational overview of conversations, models, tools and errors." />
      <QueryState query={q}>
        {(s) => (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
              <KpiCard title="Conversations" value={fmtInt(s.totals.total)} sub={`${fmtInt(s.totals.active)} active · ${fmtInt(s.totals.completed)} completed · ${fmtInt(s.totals.aborted)} aborted`} />
              <KpiCard title="Resolution rate" value={fmtPct(s.rates.resolutionRate)} accent="green" />
              <KpiCard title="Escalation rate" value={fmtPct(s.rates.escalationRate)} accent="amber" />
              <KpiCard title="Turn p95" value={<Latency ms={s.latency.turnP95Ms} />} sub={<span>p50 <Latency ms={s.latency.turnP50Ms} /></span>} accent="sky" />
              <KpiCard title="Est. cost" value={fmtUsd(s.cost.estimatedUsd)} sub={`${fmtInt(s.tokens.total)} tokens`} accent="amber" />
              <KpiCard
                title="Procedures"
                value={`${fmtInt(s.procedures.success)} ok`}
                sub={`${fmtInt(s.procedures.failure)} failed · ${fmtInt(s.procedures.clarification)} clarifications`}
                accent="green"
              />
            </div>

            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <DonutChartCard title="Channel split" data={recordToNameValue({ chat: s.byChannel.chat, voice: s.byChannel.voice })} />
              <BarChartCard title="Tier usage" data={recordToNameValue(s.models.byTier)} />
              <BarChartCard title="Provider usage" data={recordToNameValue(s.models.byProvider)} />
              <BarChartCard title="Model usage" data={recordToNameValue(s.models.byModel)} />
            </div>

            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <div>
                <h2 className="mb-2 text-sm font-semibold text-slate-700">Tool success / failure</h2>
                {s.tools.length === 0 ? (
                  <EmptyState title="No tool calls recorded" />
                ) : (
                  <DataTable
                    columns={[
                      { key: 'name', header: 'Tool' },
                      { key: 'calls', header: 'Calls', render: (r) => fmtInt(r.calls) },
                      { key: 'successRate', header: 'Success', render: (r) => fmtPct(r.successRate) },
                      { key: 'meanMs', header: 'Mean', render: (r) => <Latency ms={r.meanMs} /> },
                    ]}
                    rows={s.tools}
                    rowKey={(r) => r.name}
                  />
                )}
              </div>
              <div>
                <h2 className="mb-2 text-sm font-semibold text-slate-700">
                  RAG <span className="font-normal text-slate-400">· {fmtInt(s.rag.searches)} searches · hit rate {fmtPct(s.rag.cacheHitRate)} · mean <Latency ms={s.rag.meanMs} /></span>
                </h2>
                <h2 className="mb-2 mt-4 text-sm font-semibold text-slate-700">Recent errors</h2>
                {s.recentErrors.length === 0 ? (
                  <EmptyState title="No recent errors" hint="Failed/aborted turns will appear here." />
                ) : (
                  <DataTable
                    columns={[
                      { key: 'at', header: 'At', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.at)}</span> },
                      {
                        key: 'sessionId',
                        header: 'Session',
                        render: (r) => (
                          <Link to={`/conversations/${encodeURIComponent(r.sessionId)}`} className="font-mono text-xs text-indigo-600 hover:underline">
                            {r.sessionId.slice(0, 18)}…
                          </Link>
                        ),
                      },
                      { key: 'turnNumber', header: 'Turn' },
                      { key: 'outcome', header: 'Outcome', render: (r) => <StatusBadge value={r.outcome} /> },
                      { key: 'errorCode', header: 'Error', render: (r) => r.errorCode ?? <span className="text-slate-400">—</span> },
                    ]}
                    rows={s.recentErrors}
                    rowKey={(r) => r.traceId}
                  />
                )}
              </div>
            </div>
          </div>
        )}
      </QueryState>
    </div>
  );
}
