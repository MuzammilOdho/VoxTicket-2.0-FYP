import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useConversationDetail, useTurnTrace, useVoiceCallDetail } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, LoadingSpinner, QueryState, errorMessage } from '../components/States';
import { StatusBadge, BoolBadge } from '../components/StatusBadge';
import { DecisionTrace } from '../components/DecisionTrace';
import { Timeline } from '../components/Timeline';
import { fmtTime } from '../components/KpiCard';
import type { TimelineEntry, VoiceTurnMetric } from '../api/types';

function TurnTraceBlock({ traceId, turnNumber, voice }: { traceId: string; turnNumber: number; voice?: VoiceTurnMetric | null }) {
  const [open, setOpen] = useState(false);
  const q = useTurnTrace(traceId, open, turnNumber);
  return (
    <div className="mt-2 rounded-xl border border-line bg-raised">
      <button onClick={() => setOpen((o) => !o)} className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium text-signal">
        <span>{open ? '▾' : '▸'}</span> AI decision trace
        <span className="font-mono text-[11px] font-normal text-ink-mute">{traceId.slice(0, 16)}…</span>
      </button>
      {open && (
        <div className="border-t border-line-soft p-3">
          {q.isLoading && <LoadingSpinner label="Loading trace..." />}
          {q.isError && <div className="text-sm text-bad">{errorMessage(q.error)}</div>}
          {q.data && <DecisionTrace trace={q.data} voice={voice} />}
        </div>
      )}
    </div>
  );
}

function TurnCard({
  turnNumber,
  entries,
  voice,
}: {
  turnNumber: number;
  entries: TimelineEntry[];
  voice?: VoiceTurnMetric | null;
}) {
  const traceEntry = entries.find((e) => e.kind === 'TRACE' && e.traceId);
  const userMsg = entries.find((e) => e.kind === 'MESSAGE' && /user/i.test(e.label));
  const assistantMsg = entries.find((e) => e.kind === 'MESSAGE' && /assistant/i.test(e.label));
  return (
    <div className="rounded-xl border border-line bg-raised p-4">
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <span className="font-mono text-sm font-semibold text-ink-dim">Turn #{turnNumber}</span>
        {voice?.bargeIn && <StatusBadge value="BARGE_IN" />}
        {voice?.aborted && <StatusBadge value="ABORTED" />}
        {voice && !voice.aborted && !voice.error && <StatusBadge value="OK" label="voice ok" />}
        {voice?.error && <StatusBadge value="ERROR" label={voice.error} />}
      </div>
      {userMsg && (
        <div className="mb-1 rounded-xl bg-info/[0.08] p-2.5 text-sm text-ink">
          <span className="mr-2 text-xs font-medium uppercase text-info">User</span>
          {userMsg.detail ?? userMsg.label}
        </div>
      )}
      {assistantMsg && (
        <div className="mb-1 rounded-xl bg-ok/[0.08] p-2.5 text-sm text-ink">
          <span className="mr-2 text-xs font-medium uppercase text-ok">Assistant</span>
          {assistantMsg.detail ?? assistantMsg.label}
        </div>
      )}
      {traceEntry?.traceId ? (
        <TurnTraceBlock traceId={traceEntry.traceId} turnNumber={turnNumber} voice={voice} />
      ) : (
        <div className="mt-2 text-xs text-ink-mute">No decision trace recorded for this turn.</div>
      )}
    </div>
  );
}

export function ConversationDetail() {
  const { sessionId = '' } = useParams();
  const detail = useConversationDetail(sessionId);

  const channel = detail.data?.session.channel ?? '';
  const isVoice = /voice|phone/i.test(channel);
  const voiceCall = useVoiceCallDetail(sessionId, isVoice && !!detail.data);

  const turns = useMemo(() => {
    const map = new Map<number, TimelineEntry[]>();
    for (const e of detail.data?.timeline ?? []) {
      const arr = map.get(e.turnNumber) ?? [];
      arr.push(e);
      map.set(e.turnNumber, arr);
    }
    return [...map.entries()].sort((a, b) => a[0] - b[0]);
  }, [detail.data]);

  const voiceByTurn = useMemo(() => {
    const m = new Map<number, VoiceTurnMetric>();
    for (const t of voiceCall.data?.turns ?? []) m.set(t.turnNumber, t);
    return m;
  }, [voiceCall.data]);

  const s = detail.data?.session;

  return (
    <div>
      <PageHeader title="Conversation detail" sub={sessionId} />
      <QueryState query={detail}>
        {(d) => (
          <div className="space-y-4">
            {/* Session header */}
            <div className="rounded-xl border border-line bg-raised p-4">
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge value={s!.status} />
                <StatusBadge value={s!.channel} />
                {s!.escalated && <StatusBadge value="ESCALATED" />}
                <span className="text-sm text-ink-mute">{s!.turnCount} turns</span>
              </div>
              <div className="mt-3 grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
                <div><div className="text-xs text-ink-mute">Customer</div><div className="font-mono text-xs">{s!.customerId ?? 'anonymous'}</div></div>
                <div><div className="text-xs text-ink-mute">Identity assurance</div><div>{s!.identityAssurance ?? 'n/a'}</div></div>
                <div><div className="text-xs text-ink-mute">Started</div><div>{fmtTime(s!.startedAt)}</div></div>
                <div><div className="text-xs text-ink-mute">Last activity</div><div>{fmtTime(s!.lastActivityAt)}</div></div>
              </div>
              <div className="mt-2 text-sm text-ink-mute">
                Escalated: <BoolBadge value={s!.escalated} />
              </div>
            </div>

            {/* Per-turn decision traces */}
            <h2 className="text-sm font-semibold text-ink-dim">Turn-by-turn decision trace</h2>
            {turns.length === 0 ? (
              <EmptyState title="No turns recorded" />
            ) : (
              <div className="space-y-3">
                {turns.map(([n, entries]) => (
                  <TurnCard key={n} turnNumber={n} entries={entries} voice={voiceByTurn.get(n)} />
                ))}
              </div>
            )}

            {/* Full timeline */}
            <h2 className="text-sm font-semibold text-ink-dim">Full timeline</h2>
            <Timeline entries={d.timeline} />
          </div>
        )}
      </QueryState>
    </div>
  );
}
