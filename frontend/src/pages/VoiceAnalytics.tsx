import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useLiveVoiceCalls, useVoiceAnalyticsSummary, useVoiceCalls } from '../api/hooks';
import type { LiveVoiceCall, VoiceAnalyticsSummary } from '../api/types';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, ErrorState, LoadingSpinner } from '../components/States';
import { FilterBar, SelectInput } from '../components/Filters';
import { DataTable } from '../components/Table';
import { StatusBadge } from '../components/StatusBadge';
import { KpiCard, fmtInt, fmtPct, fmtTime } from '../components/KpiCard';

function dash(v: string | number | null | undefined): React.ReactNode {
  return v === null || v === undefined || v === '' ? <span className="text-ink-mute">—</span> : v;
}

/** P4: read-only live-calls panel. Auto-refreshes every 5s via the hook. */
function LiveCallsPanel() {
  const q = useLiveVoiceCalls();
  return (
    <section className="mb-8">
      <div className="mb-3 flex items-center gap-2">
        <h2 className="text-[15px] font-medium text-white">Live calls</h2>
        <span className="h-1.5 w-1.5 rounded-full bg-signal" />
        <span className="text-xs text-ink-mute">auto-refreshes every 5s · read-only</span>
      </div>
      {q.isLoading ? (
        <LoadingSpinner label="Loading live calls…" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : q.data === null ? (
        <div className="border border-line bg-raised p-4 text-[13px] text-ink-mute">
          Live calls require backend P4 — the endpoint{' '}
          <span className="font-mono text-fog">GET /api/v1/admin/voice/live-calls</span> was not found.
        </div>
      ) : q.data.length === 0 ? (
        <EmptyState title="No live calls" hint="Calls appear here while they are in progress." />
      ) : (
        <DataTable
          columns={[
            {
              key: 'room',
              header: 'Room / Session',
              render: (r: LiveVoiceCall) => (
                <Link to={`/admin/voice/${encodeURIComponent(r.sessionId)}`} className="font-mono text-xs text-signal hover:underline">
                  {r.room ?? r.sessionId}
                </Link>
              ),
            },
            { key: 'channel', header: 'Channel', render: (r: LiveVoiceCall) => dash(r.channel) },
            { key: 'turns', header: 'Turns', render: (r: LiveVoiceCall) => fmtInt(r.turnCount) },
            { key: 'procedure', header: 'Procedure', render: (r: LiveVoiceCall) => dash(r.procedure) },
            { key: 'stack', header: 'Stack depth', render: (r: LiveVoiceCall) => fmtInt(r.pendingStackDepth) },
            { key: 'interruption', header: 'Last interruption', render: (r: LiveVoiceCall) => dash(r.lastInterruption) },
          ]}
          rows={q.data}
          rowKey={(r: LiveVoiceCall) => r.sessionId}
        />
      )}
    </section>
  );
}

/** P4: aggregate voice analytics summary cards. */
function AnalyticsSummaryCards() {
  const q = useVoiceAnalyticsSummary();
  if (q.isLoading) return <LoadingSpinner label="Loading analytics summary…" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const s: VoiceAnalyticsSummary | null | undefined = q.data;
  if (s === null) {
    return (
      <div className="mb-8 border border-line bg-raised p-4 text-[13px] text-ink-mute">
        Analytics summary requires backend P4 — the endpoint{' '}
        <span className="font-mono text-fog">GET /api/v1/admin/voice/analytics/summary</span> was not found.
      </div>
    );
  }
  if (s === undefined) return null;
  const top = (s.topIntents ?? []).slice(0, 5);
  return (
    <section className="mb-8">
      <h2 className="mb-3 text-[15px] font-medium text-white">Voice analytics</h2>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <KpiCard title="Calls" value={fmtInt(s.calls)} />
        <KpiCard title="Completion" value={fmtPct(s.completionRate)} accent="green" />
        <KpiCard title="Barge-in" value={fmtPct(s.bargeInRate)} />
        <KpiCard title="Stall" value={fmtPct(s.stallRate)} accent="amber" />
        <KpiCard title="Clarification" value={fmtPct(s.clarificationRate)} accent="sky" />
        <KpiCard title="Handoff" value={fmtPct(s.handoffRate)} accent="red" />
        <KpiCard title="Avg turns / call" value={s.avgTurns == null ? 'n/a' : s.avgTurns.toFixed(1)} />
        <KpiCard
          title="Top intents"
          value={top.length === 0 ? <span className="text-base text-ink-mute">—</span> : fmtInt(top[0].count)}
          sub={top.length === 0 ? undefined : top.map((t) => `${t.intent} (${fmtInt(t.count)})`).join(' · ')}
        />
      </div>
    </section>
  );
}

export function VoiceAnalytics() {
  const [page, setPage] = useState(0);
  const [outcome, setOutcome] = useState('');
  const q = useVoiceCalls(page, 20, outcome || undefined);

  return (
    <div>
      <PageHeader title="Voice analytics" sub="LiveKit calls: outcomes, barge-ins, disconnects and stage latencies." />
      <LiveCallsPanel />
      <AnalyticsSummaryCards />
      <h2 className="mb-3 text-[15px] font-medium text-white">Call history</h2>
      <FilterBar onReset={() => { setOutcome(''); setPage(0); }}>
        <SelectInput
          label="Outcome"
          value={outcome}
          onChange={(v) => { setOutcome(v); setPage(0); }}
          options={[{ value: 'COMPLETED', label: 'Completed' }, { value: 'ABORTED', label: 'Aborted' }]}
        />
      </FilterBar>
      {q.isLoading ? (
        <LoadingSpinner />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data || q.data.content.length === 0 ? (
        <EmptyState title="No voice calls recorded" hint="Calls appear once the voice worker reports telemetry." />
      ) : (
        <DataTable
          columns={[
            {
              key: 'room',
              header: 'Room / Session',
              render: (r) => (
                <Link to={`/admin/voice/${encodeURIComponent(r.sessionId)}`} className="font-mono text-xs text-signal hover:underline">
                  {r.room}
                </Link>
              ),
            },
            { key: 'outcome', header: 'Outcome', render: (r) => <StatusBadge value={r.outcome} /> },
            { key: 'turnCount', header: 'Turns', render: (r) => fmtInt(r.turnCount) },
            { key: 'bargeInCount', header: 'Barge-ins', render: (r) => fmtInt(r.bargeInCount) },
            { key: 'sttProvider', header: 'STT', render: (r) => r.sttProvider ?? <span className="text-ink-mute">—</span> },
            { key: 'ttsProvider', header: 'TTS', render: (r) => r.ttsProvider ?? <span className="text-ink-mute">—</span> },
            { key: 'startedAt', header: 'Started', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.startedAt)}</span> },
            { key: 'endedAt', header: 'Ended', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.endedAt)}</span> },
          ]}
          rows={q.data.content}
          rowKey={(r) => r.sessionId}
          page={q.data.page}
          size={q.data.size}
          totalPages={q.data.totalPages}
          totalElements={q.data.totalElements}
          onPage={setPage}
        />
      )}
    </div>
  );
}
