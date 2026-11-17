import { Link, useParams } from 'react-router-dom';
import { useVoiceCallDetail } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { Latency } from '../components/Latency';
import { DataTable } from '../components/Table';
import { Button } from '../components/Button';
import { pushRecent } from '../components/CommandPalette';
import { useEffect } from 'react';
import { fmtTime } from '../components/KpiCard';
import { downloadCsv } from '../components/csv';
import { FactGrid, PipelineLegend, PipelineStrip, Section } from '../components/Card';

/** Mean of the non-null values, or null when there are none. */
function mean(values: (number | null | undefined)[]): number | null {
  const xs = values.filter((v): v is number => v !== null && v !== undefined && !Number.isNaN(v));
  if (xs.length === 0) return null;
  return xs.reduce((a, b) => a + b, 0) / xs.length;
}

/** Nearest-rank percentile of the non-null values, or null when there are none. */
function percentile(values: (number | null | undefined)[], p: number): number | null {
  const xs = values
    .filter((v): v is number => v !== null && v !== undefined && !Number.isNaN(v))
    .sort((a, b) => a - b);
  if (xs.length === 0) return null;
  return xs[Math.max(0, Math.ceil((p / 100) * xs.length) - 1)];
}

export function VoiceCallDetail() {
  const { sessionId = '' } = useParams();
  const q = useVoiceCallDetail(sessionId, true);
  // Palette recents: this call's room, so the operator can jump back here.
  useEffect(() => {
    if (q.data) pushRecent({ label: `Call ${q.data.call.room}`, hint: 'Voice', to: `/admin/voice/${encodeURIComponent(sessionId)}` });
  }, [q.data, sessionId]);

  return (
    <div>
      <PageHeader
        title="Voice call detail"
        crumbs={[
          { label: 'Voice', to: '/admin/voice' },
          { label: sessionId.slice(0, 8) + '…' },
        ]}
        sub={
          <span>
            Session{' '}
            <Link to={`/admin/conversations/${encodeURIComponent(sessionId)}`} className="font-mono text-signal hover:underline hover:underline-offset-4">
              {sessionId}
            </Link>
            {' · '}
            <Link to={`/admin/conversations/${encodeURIComponent(sessionId)}`} className="text-signal hover:underline hover:underline-offset-4">
              View conversation transcript →
            </Link>
          </span>
        }
      />
      <QueryState query={q} emptyTitle="No voice call record">
        {(d) =>
          d === null ? (
            <EmptyState title="No voice call record for this session" hint="This session may be a chat conversation." />
          ) : (
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-sm font-medium text-ink">{d.call.room}</span>
                <StatusBadge value={d.call.outcome} />
                <span className="text-sm text-ink-mute">{d.call.turnCount} turns · {d.call.bargeInCount} barge-ins</span>
              </div>
              <div className="mb-6 rounded-xl border border-line bg-paper p-5 shadow-card">
                <FactGrid
                  items={[
                    { label: 'STT provider', value: d.call.sttProvider ?? 'n/a' },
                    { label: 'TTS provider', value: d.call.ttsProvider ?? 'n/a' },
                    { label: 'Started', value: fmtTime(d.call.startedAt) },
                    { label: 'Ended', value: fmtTime(d.call.endedAt) },
                  ]}
                />
              </div>

              <Section title="Call aggregates">
                <div className="mb-3 flex items-center justify-between gap-2">
                  <span className="text-[13px] text-ink-mute">Computed from the per-turn metrics below.</span>
                  <Button
                    variant="ghost"
                    onClick={() =>
                      downloadCsv(`voxticket-voice-turns-${d.call.room}.csv`,
                        ['turn', 'sttMs', 'brainTtftMs', 'ttsFirstAudioMs', 'e2eMs', 'sttLanguage', 'aborted', 'bargeIn', 'error'],
                        d.turns.map((t) => [t.turnNumber, t.sttLatencyMs, t.brainTtftMs, t.ttsFirstAudioMs, t.e2eMs, t.sttLanguage, t.aborted ? 'yes' : 'no', t.bargeIn ? 'yes' : 'no', t.error]))
                    }
                  >
                    Export turns CSV
                  </Button>
                </div>
                <FactGrid
                  items={[
                    { label: 'Mean end-to-end', value: <Latency ms={mean(d.turns.map((t) => t.e2eMs))} /> },
                    { label: 'End-to-end p95', value: <Latency ms={percentile(d.turns.map((t) => t.e2eMs), 95)} /> },
                    { label: 'Mean response TTFT', value: <Latency ms={mean(d.turns.map((t) => t.brainTtftMs))} /> },
                    { label: 'Mean STT commit', value: <Latency ms={mean(d.turns.map((t) => t.sttLatencyMs))} /> },
                    { label: 'Mean TTS first audio', value: <Latency ms={mean(d.turns.map((t) => t.ttsFirstAudioMs))} /> },
                    { label: 'Slowest turn', value: d.turns.length > 0 ? `Turn ${d.turns.reduce((a, b) => (b.e2eMs ?? 0) > (a.e2eMs ?? 0) ? b : a).turnNumber}` : 'n/a' },
                  ]}
                />
              </Section>

              <Section title="Per-turn stage latencies" note="Each strip is one turn scaled to its end-to-end time, so a slow stage is visible without reading the numbers.">
                <PipelineLegend />
                {d.turns.length === 0 ? (
                  <EmptyState title="No turn metrics" />
                ) : (
                  <DataTable
                    columns={[
                      { key: 'turnNumber', header: 'Turn', sortable: true, sortValue: (r) => r.turnNumber },
                      {
                        key: 'pipeline',
                        header: 'Pipeline',
                        render: (r) => (
                          <PipelineStrip sttMs={r.sttLatencyMs} brainMs={r.brainTtftMs} ttsMs={r.ttsFirstAudioMs} e2eMs={r.e2eMs} />
                        ),
                      },
                      { key: 'sttLatencyMs', header: 'STT', render: (r) => <Latency ms={r.sttLatencyMs} maxMs={r.e2eMs ?? undefined} />, sortable: true, sortValue: (r) => r.sttLatencyMs },
                      { key: 'brainTtftMs', header: 'Response TTFT', render: (r) => <Latency ms={r.brainTtftMs} maxMs={r.e2eMs ?? undefined} />, sortable: true, sortValue: (r) => r.brainTtftMs },
                      { key: 'ttsFirstAudioMs', header: 'TTS first audio', render: (r) => <Latency ms={r.ttsFirstAudioMs} maxMs={r.e2eMs ?? undefined} />, sortable: true, sortValue: (r) => r.ttsFirstAudioMs },
                      { key: 'e2eMs', header: 'End-to-end', render: (r) => <Latency ms={r.e2eMs} />, sortable: true, sortValue: (r) => r.e2eMs },
                      { key: 'sttLanguage', header: 'STT lang', render: (r) => r.sttLanguage ?? <span className="text-ink-mute">—</span> },
                      {
                        key: 'flags',
                        header: 'Flags',
                        render: (r) => (
                          <span className="flex gap-1">
                            {r.aborted && <StatusBadge value="ABORTED" />}
                            {r.bargeIn && <StatusBadge value="BARGE_IN" />}
                            {r.error && <StatusBadge value="ERROR" label={r.error} />}
                            {!r.aborted && !r.bargeIn && !r.error && <StatusBadge value="OK" />}
                          </span>
                        ),
                      },
                    ]}
                    rows={d.turns}
                    rowKey={(r) => `${r.turnNumber}-${r.traceId ?? 'x'}`}
                  />
                )}
              </Section>
            </div>
          )
        }
      </QueryState>
    </div>
  );
}
