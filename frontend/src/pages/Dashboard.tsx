/** Dashboard (Overview) — Attio system (DESIGN.md).
 *
 * Order is the design. The old page showed six KPI boxes, four rainbow
 * charts, and the only actionable content (recent errors) crammed into
 * the bottom-right corner, with no voice section at all for a
 * voice-first system. This page answers, in order: the headline
 * numbers, whether voice is healthy, where the turns go, then the tool
 * and retrieval detail and the error detail. Model and provider detail
 * is deliberately NOT repeated
 * here — it lives on AI Models, linked once.
 *
 * Colour rule (DESIGN-ELEVENLABS.md): black is the only action
 * colour; green/amber/red appear only as outcome badges. The four chart
 * cards are replaced by two single-hue bar lists with directly labelled
 * values (no rotated axis labels, no legend to decode).
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useSummary } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, TextInput } from '../components/Filters';
import { KpiCard, fmtInt, fmtPct, fmtUsd, fmtTime } from '../components/KpiCard';
import { Latency } from '../components/Latency';
import { StatusBadge } from '../components/StatusBadge';
import { DataTable } from '../components/Table';
import { Button } from '../components/Button';
import { Section } from '../components/Card';

/* Bar colour: a row may carry its own colour (the channel split:
 * Chat is brand indigo, Voice is teal — two categories, two colours).
 * Otherwise the largest bar is brand and the rest warm gray, so the
 * highlight still reads at a glance. */
function BarList({ rows }: { rows: { label: string; value: number; color?: string }[] }) {
  const total = rows.reduce((a, r) => a + r.value, 0);
  const max = Math.max(...rows.map((r) => r.value));
  if (total === 0) return <EmptyState title="No data in this window" />;
  return (
    <div>
      {rows.map((r) => (
        <div key={r.label} className="grid grid-cols-[140px_1fr_88px] items-center gap-3 py-1.5 text-[13.5px]">
          <span className="truncate text-ink">{r.label}</span>
          <span className="block h-2.5 overflow-hidden rounded-full bg-line-soft">
            <span
              className={`block h-full rounded-full ${r.color ?? (r.value === max ? 'bg-signal' : 'bg-ink-mute')}`}
              style={{ width: `${Math.max(2, (r.value / total) * 100)}%` }}
            />
          </span>
          <span className="text-right font-mono text-[12.5px] text-ink-mute">
            {fmtInt(r.value)} · {((r.value / total) * 100).toFixed(0)}%
          </span>
        </div>
      ))}
    </div>
  );
}

export function Dashboard() {
  const [draft, setDraft] = useState({ from: '', to: '' });
  const [applied, setApplied] = useState({ from: '', to: '' });
  const q = useSummary(applied.from || undefined, applied.to || undefined);
  return (
    <div>
      <PageHeader
        title="Overview"
        sub="The headline numbers, whether voice is healthy, where the turns go, and what the errors say."
      />
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
          Window {applied.from || '…'} to {applied.to || '…'} (UTC days). Sessions count by start, turns and
          events by end/creation; every section on this page, including models, tokens, cost, tools and RAG,
          follows the same window.
        </p>
      ) : null}
      <QueryState query={q}>
        {(s) => {
          return (
            <div>
              {/* KPI card grid: five numbers with context. */}
              <div className="mb-6 grid grid-cols-2 gap-6 rounded-xl border border-line bg-paper p-5 shadow-card md:grid-cols-5">
                {[
                  { label: 'Conversations', dot: 'bg-brand', value: fmtInt(s.totals.total), note: <><span className="whitespace-nowrap">{fmtInt(s.totals.active)} active</span> · <span className="whitespace-nowrap">{fmtInt(s.totals.completed)} done</span> · <span className="whitespace-nowrap">{fmtInt(s.totals.aborted)} aborted</span></> },
                  { label: 'Resolution rate', dot: 'bg-ok', value: fmtPct(s.rates.resolutionRate), note: 'normal outcomes / terminal turns' },
                  { label: 'Turn p95', dot: 'bg-teal', value: <Latency ms={s.latency.turnP95Ms} />, note: <>p50 <Latency ms={s.latency.turnP50Ms} /> <span className="whitespace-nowrap">· chat + voice</span></> },
                  { label: 'Est. cost', dot: 'bg-amber', value: fmtUsd(s.cost.estimatedUsd), note: `${fmtInt(s.tokens.total)} tokens` },
                  { label: 'Procedures', dot: 'bg-violet', value: `${fmtInt(s.procedures.success)} ok`, note: <><span className="whitespace-nowrap">{fmtInt(s.procedures.failure)} failed</span> · <span className="whitespace-nowrap">{fmtInt(s.procedures.clarification)} clarifications</span></> },
                ].map((k) => (
                  <KpiCard key={k.label} title={k.label} dot={k.dot} value={k.value} sub={k.note} />
                ))}
              </div>

              <Section
                title="Voice health"
                note={<>This is a voice-first system, so voice gets its own section instead of being buried in a channel split. <Link to="/admin/voice" className="font-medium text-signal hover:underline hover:underline-offset-4">All voice calls →</Link></>}
              >
                {s.recentVoiceCalls.length === 0 ? (
                  <EmptyState title="No voice calls in this window" hint="Voice calls appear here once they are recorded." />
                ) : (
                  <DataTable
                    columns={[
                      { key: 'room', header: 'Room', render: (r) => (
                        <Link to={`/admin/voice/${encodeURIComponent(r.room)}`} className="font-mono text-xs text-signal hover:underline hover:underline-offset-4">
                          {r.room}
                        </Link>
                      ) },
                      { key: 'outcome', header: 'Outcome', render: (r) => <StatusBadge value={r.outcome} /> },
                      { key: 'turnCount', header: 'Turns', render: (r) => fmtInt(r.turnCount) },
                      { key: 'avgE2eMs', header: 'Mean end-to-end', render: (r) => <Latency ms={r.avgE2eMs} /> },
                      { key: 'avgBrainMs', header: 'Mean response', render: (r) => <Latency ms={r.avgBrainMs} /> },
                      { key: 'startedAt', header: 'Started', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.startedAt)}</span> },
                    ]}
                    rows={s.recentVoiceCalls}
                    rowKey={(r) => r.room}
                  />
                )}
              </Section>

              <Section
                title="Where the turns go"
                note={<>Two splits, values labelled directly; Chat is indigo, Voice is teal, and the largest tier bar is indigo, the rest gray. Model and provider detail lives on <Link to="/admin/ai/models" className="font-medium text-signal hover:underline hover:underline-offset-4">AI Models →</Link>, not repeated here.</>}
              >
                <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                  <div>
                    <div className="mb-1 font-medium text-ink">Channel</div>
                    <BarList rows={[
                      { label: 'Chat', value: s.byChannel.chat, color: 'bg-brand' },
                      { label: 'Voice', value: s.byChannel.voice, color: 'bg-teal' },
                    ]} />
                  </div>
                  <div>
                    <div className="mb-1 font-medium text-ink">Tier</div>
                    <BarList rows={Object.entries(s.models.byTier).map(([label, value]) => ({ label, value }))} />
                  </div>
                </div>
              </Section>

              <Section title="Tools and retrieval" note="Tools as a table because names, counts, and success rates need comparing. Retrieval as three numbers, not a sentence.">
                <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                  <div>
                    {s.tools.length === 0 ? (
                      <EmptyState title="No tool calls recorded" />
                    ) : (
                      <DataTable
                        columns={[
                          { key: 'name', header: 'Tool', sortable: true, sortValue: (r) => r.name },
                          { key: 'calls', header: 'Calls', render: (r) => fmtInt(r.calls), sortable: true, sortValue: (r) => r.calls },
                          { key: 'successRate', header: 'Success', render: (r) => fmtPct(r.successRate), sortable: true, sortValue: (r) => r.successRate },
                          { key: 'meanMs', header: 'Mean', render: (r) => <Latency ms={r.meanMs} />, sortable: true, sortValue: (r) => r.meanMs },
                        ]}
                        rows={s.tools}
                        rowKey={(r) => r.name}
                      />
                    )}
                  </div>
                  <div>
                    <div className="flex flex-wrap gap-x-6 gap-y-3 border-y border-line py-3">
                      <span><span className="text-[19px] font-medium text-ink">{fmtInt(s.rag.searches)}</span><br /><span className="text-[13px] text-ink-mute">RAG searches</span></span>
                      <span><span className="text-[19px] font-medium text-ink">{fmtPct(s.rag.cacheHitRate)}</span><br /><span className="text-[13px] text-ink-mute">Cache hit rate</span></span>
                      <span><span className="text-[19px] font-medium text-ink"><Latency ms={s.rag.meanMs} /></span><br /><span className="text-[13px] text-ink-mute">Mean retrieval</span></span>
                    </div>
                    <p className="mt-3 text-[13px] text-ink-mute">
                      A falling hit rate or a rising mean here is the first sign the knowledge base or the
                      embedding path needs attention, before users notice wrong answers.
                    </p>
                  </div>
                </div>
              </Section>

              <Section id="recent-errors" title="Recent errors" note="Failed or aborted turns in this window. Each session opens its conversation and trace.">
                {s.recentErrors.length === 0 ? (
                  <EmptyState title="No recent errors" hint="Failed/aborted turns will appear here." />
                ) : (
                  <DataTable
                    columns={[
                      { key: 'at', header: 'At', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.at)}</span> },
                      { key: 'sessionId', header: 'Session', render: (r) => (
                        <Link to={`/admin/conversations/${encodeURIComponent(r.sessionId)}`} className="font-mono text-xs text-signal hover:underline hover:underline-offset-4">
                          {r.sessionId.slice(0, 18)}…
                        </Link>
                      ) },
                      { key: 'turnNumber', header: 'Turn' },
                      { key: 'outcome', header: 'Outcome', render: (r) => <StatusBadge value={r.outcome} /> },
                      { key: 'errorCode', header: 'Error', render: (r) => r.errorCode ?? <span className="text-ink-mute">—</span> },
                    ]}
                    rows={s.recentErrors}
                    rowKey={(r) => r.traceId}
                  />
                )}
              </Section>
            </div>
          );
        }}
      </QueryState>
    </div>
  );
}
