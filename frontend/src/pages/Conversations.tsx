import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useConversations, type ConversationFilters } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, SelectInput, TextInput } from '../components/Filters';
import { DataTable } from '../components/Table';
import { Button } from '../components/Button';
import { StatusBadge, BoolBadge } from '../components/StatusBadge';
import { fmtTime } from '../components/KpiCard';

const EMPTY: ConversationFilters = { page: 0, size: 20 };

export function Conversations() {
  const [f, setF] = useState<ConversationFilters>(EMPTY);
  const [draft, setDraft] = useState({ channel: '', status: '', outcome: '', escalated: '', q: '', from: '', to: '' });
  const q = useConversations(f);

  function apply() {
    setF({
      page: 0,
      size: f.size,
      channel: draft.channel || undefined,
      status: draft.status || undefined,
      outcome: draft.outcome || undefined,
      escalated: draft.escalated || undefined,
      q: draft.q || undefined,
      from: draft.from || undefined,
      to: draft.to || undefined,
    });
  }

  return (
    <div>
      <PageHeader title="Conversations" sub="Search and filter all conversations, then drill into the decision trace." />
      <FilterBar
        dateNote
        chips={[
          ...(f.channel ? [{ label: `Channel: ${f.channel}`, onRemove: () => { setF({ ...f, channel: undefined, page: 0 }); setDraft((d) => ({ ...d, channel: '' })); } }] : []),
          ...(f.status ? [{ label: `Status: ${f.status}`, onRemove: () => { setF({ ...f, status: undefined, page: 0 }); setDraft((d) => ({ ...d, status: '' })); } }] : []),
          ...(f.outcome ? [{ label: `Outcome: ${f.outcome}`, onRemove: () => { setF({ ...f, outcome: undefined, page: 0 }); setDraft((d) => ({ ...d, outcome: '' })); } }] : []),
          ...(f.escalated ? [{ label: `Escalated: ${f.escalated}`, onRemove: () => { setF({ ...f, escalated: undefined, page: 0 }); setDraft((d) => ({ ...d, escalated: '' })); } }] : []),
          ...(f.q ? [{ label: `Search: ${f.q}`, onRemove: () => { setF({ ...f, q: undefined, page: 0 }); setDraft((d) => ({ ...d, q: '' })); } }] : []),
          ...(f.from || f.to ? [{ label: `Window: ${f.from ?? '…'} → ${f.to ?? '…'}`, onRemove: () => { setF({ ...f, from: undefined, to: undefined, page: 0 }); setDraft((d) => ({ ...d, from: '', to: '' })); } }] : []),
        ]}
        onReset={() => { setDraft({ channel: '', status: '', outcome: '', escalated: '', q: '', from: '', to: '' }); setF(EMPTY); }}>
        <SelectInput label="Channel" value={draft.channel} onChange={(v) => setDraft({ ...draft, channel: v })} options={[{ value: 'CHAT', label: 'Chat' }, { value: 'VOICE', label: 'Voice' }]} />
        <SelectInput label="Status" value={draft.status} onChange={(v) => setDraft({ ...draft, status: v })} options={['ACTIVE', 'COMPLETED', 'ABORTED', 'EXPIRED'].map((v) => ({ value: v, label: v }))} />
        <TextInput label="Last outcome" placeholder="e.g. normal" value={draft.outcome} onChange={(e) => setDraft({ ...draft, outcome: e.target.value })} />
        <SelectInput label="Escalated" value={draft.escalated} onChange={(v) => setDraft({ ...draft, escalated: v })} options={[{ value: 'true', label: 'Yes' }, { value: 'false', label: 'No' }]} />
        <TextInput label="Search" placeholder="session / customer" value={draft.q} onChange={(e) => setDraft({ ...draft, q: e.target.value })} />
        <TextInput label="From" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        <TextInput label="To" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        <Button onClick={apply}>Apply</Button>
      </FilterBar>

      <QueryState query={q} emptyTitle="No conversations" skeleton>
        {(page) =>
          page.content.length === 0 ? (
            <EmptyState title="No conversations match" hint="Adjust the filters." />
          ) : (
            <DataTable
              columns={[
                {
                  key: 'sessionId',
                  header: 'Session',
                  render: (r) => (
                    <Link to={`/admin/conversations/${encodeURIComponent(r.sessionId)}`} className="font-mono text-xs text-signal hover:underline hover:underline-offset-4">
                      {r.sessionId}
                    </Link>
                  ),
                },
                { key: 'channel', header: 'Channel', render: (r) => <StatusBadge value={r.channel} /> },
                { key: 'status', header: 'Status', render: (r) => <StatusBadge value={r.status} /> },
                { key: 'lastTurnOutcome', header: 'Last outcome', render: (r) => r.lastTurnOutcome ? <StatusBadge value={r.lastTurnOutcome} /> : <span className="text-ink-mute">—</span> },
                { key: 'turnCount', header: 'Turns' },
                { key: 'escalated', header: 'Escalated', render: (r) => <BoolBadge value={r.escalated} /> },
                { key: 'startedAt', header: 'Started', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.startedAt)}</span> },
                { key: 'lastActivityAt', header: 'Last activity', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.lastActivityAt)}</span> },
              ]}
              rows={page.content}
              rowKey={(r) => r.sessionId}
              page={page.page}
              size={page.size}
              totalPages={page.totalPages}
              totalElements={page.totalElements}
              onPage={(p) => setF({ ...f, page: p })}
              onSizeChange={(n) => setF({ ...f, size: n, page: 0 })}
            />
          )
        }
      </QueryState>
    </div>
  );
}
