import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuditEvents, type AuditFilters } from '../api/hooks';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, SelectInput, TextInput } from '../components/Filters';
import { DataTable } from '../components/Table';
import { Button } from '../components/Button';
import { StatusBadge } from '../components/StatusBadge';
import { fmtTime } from '../components/KpiCard';

const EMPTY: AuditFilters = { page: 0, size: 20 };

export function AuditLog() {
  const [f, setF] = useState<AuditFilters>(EMPTY);
  const [draft, setDraft] = useState({ sessionId: '', type: '', from: '', to: '' });
  const q = useAuditEvents(f);

  function apply() {
    setF({
      page: 0,
      size: f.size,
      sessionId: draft.sessionId || undefined,
      type: draft.type || undefined,
      from: draft.from || undefined,
      to: draft.to || undefined,
    });
  }

  return (
    <div>
      <PageHeader title="Audit log" sub="Business events and procedure/action history with success/failure classification." />
      <FilterBar
        dateNote
        chips={[
          ...(f.sessionId ? [{ label: `Session: ${f.sessionId}`, onRemove: () => { setF({ ...f, sessionId: undefined, page: 0 }); setDraft((d) => ({ ...d, sessionId: '' })); } }] : []),
          ...(f.type ? [{ label: `Type: ${f.type}`, onRemove: () => { setF({ ...f, type: undefined, page: 0 }); setDraft((d) => ({ ...d, type: '' })); } }] : []),
          ...(f.from || f.to ? [{ label: `Window: ${f.from ?? '…'} → ${f.to ?? '…'}`, onRemove: () => { setF({ ...f, from: undefined, to: undefined, page: 0 }); setDraft((d) => ({ ...d, from: '', to: '' })); } }] : []),
        ]}
        onReset={() => { setDraft({ sessionId: '', type: '', from: '', to: '' }); setF(EMPTY); }}>
        <TextInput label="Session" placeholder="session id" value={draft.sessionId} onChange={(e) => setDraft({ ...draft, sessionId: e.target.value })} />
        <SelectInput
          label="Event type"
          value={draft.type}
          onChange={(v) => setDraft({ ...draft, type: v })}
          options={['MODEL_SELECTED', 'TOOL_CALLED', 'RAG_SEARCH', 'PROCEDURE_STARTED', 'PROCEDURE_COMPLETED', 'PROCEDURE_FAILED', 'PROCEDURE_CLARIFICATION', 'PROCEDURE_DEFERRED', 'OTP_ISSUED', 'OTP_VERIFIED', 'OTP_FAILED', 'EXECUTION_SUCCEEDED', 'EXECUTION_FAILED', 'ESCALATED', 'SAFETY_BLOCKED', 'SUSPECTED_FABRICATION'].map((v) => ({ value: v, label: v }))}
        />
        <TextInput label="From" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        <TextInput label="To" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        <Button onClick={apply}>Apply</Button>
      </FilterBar>
      <QueryState query={q} emptyTitle="No audit events" skeleton>
        {(p) =>
          p.content.length === 0 ? (
            <EmptyState title="No audit events match" hint="Adjust the filters." />
          ) : (
            <DataTable
              columns={[
                { key: 'at', header: 'At', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.at)}</span> },
                {
                  key: 'sessionId',
                  header: 'Session',
                  render: (r) => (
                    <Link to={`/admin/conversations/${encodeURIComponent(r.sessionId)}`} className="font-mono text-xs text-signal hover:underline hover:underline-offset-4">
                      {r.sessionId.slice(0, 20)}…
                    </Link>
                  ),
                },
                { key: 'turnNumber', header: 'Turn', render: (r) => r.turnNumber ?? <span className="text-ink-mute">—</span> },
                { key: 'type', header: 'Type', render: (r) => <StatusBadge value={r.type} /> },
                { key: 'detail', header: 'Detail', render: (r) => <span className="max-w-md break-words font-mono text-xs">{r.detail ?? '—'}</span>, className: 'max-w-md' },
                { key: 'traceId', header: 'Trace', render: (r) => <span className="font-mono text-xs text-ink-mute">{r.traceId ? r.traceId.slice(0, 12) + '…' : '—'}</span> },
              ]}
              rows={p.content}
              rowKey={(_, i) => `${p.page}-${i}`}
              page={p.page}
              size={p.size}
              totalPages={p.totalPages}
              totalElements={p.totalElements}
              onPage={(pg) => setF({ ...f, page: pg })}
              onSizeChange={(n) => setF({ ...f, size: n, page: 0 })}
            />
          )
        }
      </QueryState>
    </div>
  );
}
