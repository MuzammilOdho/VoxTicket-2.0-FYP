import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useConversationDetail, useTurnTrace, useVoiceCallDetail } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { pushRecent } from '../components/CommandPalette';
import { useEffect } from 'react';
import { EmptyState, LoadingSpinner, QueryState, errorMessage } from '../components/States';
import { StatusBadge, BoolBadge } from '../components/StatusBadge';
import { DecisionTrace } from '../components/DecisionTrace';
import { Timeline } from '../components/Timeline';
import { fmtTime } from '../components/KpiCard';
import { FactGrid, Section } from '../components/Card';
import type { TimelineEntry, VoiceTurnMetric } from '../api/types';

function TurnTraceBlock({ traceId, turnNumber, voice }: { traceId: string; turnNumber: number; voice?: VoiceTurnMetric | null }) {
  const [open, setOpen] = useState(false);
  const q = useTurnTrace(traceId, open, turnNumber);
  return (
    <div className="mt-2 border-y border-line-soft py-1">
      <button onClick={() => setOpen((o) => !o)} className="flex w-full items-center gap-2 py-1 text-left text-sm font-medium text-signal">
        <span>{open ? '▾' : '▸'}</span> AI decision trace
        <span className="font-mono text-[11px] font-normal text-ink-mute">{traceId.slice(0, 16)}…</span>
      </button>
      {open && (
        <div className="py-2">
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
    <div id={`turn-${turnNumber}`} className="scroll-mt-6 border-t border-line py-4">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <span className="font-mono text-sm font-medium text-ink-dim">Turn #{turnNumber}</span>
        {voice?.bargeIn && <StatusBadge value="BARGE_IN" />}
        {voice?.aborted && <StatusBadge value="ABORTED" />}
        {voice && !voice.aborted && !voice.error && <StatusBadge value="OK" label="voice ok" />}
        {voice?.error && <StatusBadge value="ERROR" label={voice.error} />}
      </div>
      {userMsg && (
        <div className="mb-2 border-l-2 border-smoke py-1 pl-3 text-sm text-ink">
          <span className="mr-2 text-xs font-medium uppercase text-ink-mute">User</span>
          {userMsg.detail ?? userMsg.label}
        </div>
      )}
      {assistantMsg && (
        <div className="mb-2 border-l-2 border-info py-1 pl-3 text-sm text-ink">
          <span className="mr-2 text-xs font-medium uppercase text-ink-mute">Assistant</span>
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
  // Palette recents: this conversation, so the operator can jump back here.
  useEffect(() => {
    if (detail.data) pushRecent({ label: `Conversation ${sessionId.slice(0, 8)}…`, hint: 'Conversations', to: `/admin/conversations/${encodeURIComponent(sessionId)}` });
  }, [detail.data, sessionId]);

  return (
    <div>
      <PageHeader
        title="Conversation detail"
        sub={sessionId}
        crumbs={[
          { label: 'Conversations', to: '/admin/conversations' },
          { label: `${sessionId.slice(0, 8)}…` },
        ]}
      />
      <QueryState query={detail}>
        {(d) => (
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <StatusBadge value={s!.status} />
              <StatusBadge value={s!.channel} />
              {s!.escalated && <StatusBadge value="ESCALATED" />}
              <span className="text-sm text-ink-mute">{s!.turnCount} turns</span>
            </div>
            <div className="mb-6 rounded-xl border border-line bg-paper p-5 shadow-card">
              <FactGrid
                items={[
                  { label: 'Customer', value: <span className="font-mono text-xs">{s!.customerId ?? 'anonymous'}</span> },
                  { label: 'Identity assurance', value: s!.identityAssurance ?? 'n/a' },
                  { label: 'Started', value: fmtTime(s!.startedAt) },
                  { label: 'Last activity', value: fmtTime(s!.lastActivityAt) },
                  { label: 'Escalated', value: <BoolBadge value={s!.escalated} /> },
                ]}
              />
            </div>

            <Section title="Turn-by-turn decision trace">
              {turns.length === 0 ? (
                <EmptyState title="No turns recorded" />
              ) : (
                <div>
                  <p className="mb-2 flex flex-wrap items-center gap-1.5 text-xs text-ink-mute">
                    <span>Jump to turn:</span>
                    {turns.map(([n]) => (
                      <a key={n} href={`#turn-${n}`} className="inline-flex min-h-[36px] min-w-[36px] items-center justify-center rounded-full border border-line bg-paper px-1.5 font-mono text-ink-dim hover:border-ink-mute hover:text-ink">
                        {n}
                      </a>
                    ))}
                  </p>
                  {turns.map(([n, entries]) => (
                    <TurnCard key={n} turnNumber={n} entries={entries} voice={voiceByTurn.get(n)} />
                  ))}
                </div>
              )}
            </Section>

            <Section title="Full timeline">
              <Timeline entries={d.timeline} />
            </Section>
          </div>
        )}
      </QueryState>
    </div>
  );
}
