import { Link, useParams } from 'react-router-dom';
import { useVoiceCallDetail } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { StatusBadge } from '../components/StatusBadge';
import { Latency } from '../components/Latency';
import { DataTable } from '../components/Table';
import { fmtTime } from '../components/KpiCard';

export function VoiceCallDetail() {
  const { sessionId = '' } = useParams();
  const q = useVoiceCallDetail(sessionId, true);

  return (
    <div>
      <PageHeader
        title="Voice call detail"
        sub={
          <span>
            Session{' '}
            <Link to={`/admin/conversations/${encodeURIComponent(sessionId)}`} className="font-mono text-signal hover:underline">
              {sessionId}
            </Link>
          </span>
        }
      />
      <QueryState query={q} emptyTitle="No voice call record">
        {(d) =>
          d === null ? (
            <EmptyState title="No voice call record for this session" hint="This session may be a chat conversation." />
          ) : (
            <div className="space-y-4">
              <div className="rounded-xl border border-line bg-raised p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-sm font-semibold">{d.call.room}</span>
                  <StatusBadge value={d.call.outcome} />
                  <span className="text-sm text-ink-mute">{d.call.turnCount} turns · {d.call.bargeInCount} barge-ins</span>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
                  <div><div className="text-xs text-ink-mute">STT provider</div><div>{d.call.sttProvider ?? 'n/a'}</div></div>
                  <div><div className="text-xs text-ink-mute">TTS provider</div><div>{d.call.ttsProvider ?? 'n/a'}</div></div>
                  <div><div className="text-xs text-ink-mute">Started</div><div>{fmtTime(d.call.startedAt)}</div></div>
                  <div><div className="text-xs text-ink-mute">Ended</div><div>{fmtTime(d.call.endedAt)}</div></div>
                </div>
              </div>

              <h2 className="text-sm font-semibold text-ink-dim">Per-turn stage latencies</h2>
              {d.turns.length === 0 ? (
                <EmptyState title="No turn metrics" />
              ) : (
                <DataTable
                  columns={[
                    { key: 'turnNumber', header: 'Turn' },
                    { key: 'sttLatencyMs', header: 'STT', render: (r) => <Latency ms={r.sttLatencyMs} maxMs={r.e2eMs ?? undefined} /> },
                    { key: 'brainTtftMs', header: 'Brain TTFT', render: (r) => <Latency ms={r.brainTtftMs} maxMs={r.e2eMs ?? undefined} /> },
                    { key: 'ttsFirstAudioMs', header: 'TTS first audio', render: (r) => <Latency ms={r.ttsFirstAudioMs} maxMs={r.e2eMs ?? undefined} /> },
                    { key: 'e2eMs', header: 'End-to-end', render: (r) => <Latency ms={r.e2eMs} /> },
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
            </div>
          )
        }
      </QueryState>
    </div>
  );
}
