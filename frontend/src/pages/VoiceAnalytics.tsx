import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useVoiceCalls } from '../api/hooks';
import { PageHeader } from '../components/Layout';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, SelectInput } from '../components/Filters';
import { DataTable } from '../components/Table';
import { StatusBadge } from '../components/StatusBadge';
import { fmtTime, fmtInt } from '../components/KpiCard';

export function VoiceAnalytics() {
  const [page, setPage] = useState(0);
  const [outcome, setOutcome] = useState('');
  const q = useVoiceCalls(page, 20, outcome || undefined);

  return (
    <div>
      <PageHeader title="Voice analytics" sub="LiveKit calls: outcomes, barge-ins, disconnects and stage latencies." />
      <FilterBar onReset={() => { setOutcome(''); setPage(0); }}>
        <SelectInput
          label="Outcome"
          value={outcome}
          onChange={(v) => { setOutcome(v); setPage(0); }}
          options={[{ value: 'COMPLETED', label: 'Completed' }, { value: 'ABORTED', label: 'Aborted' }]}
        />
      </FilterBar>
      <QueryState query={q} emptyTitle="No voice calls">
        {(p) =>
          p.content.length === 0 ? (
            <EmptyState title="No voice calls recorded" hint="Calls appear once the voice worker reports telemetry." />
          ) : (
            <DataTable
              columns={[
                {
                  key: 'room',
                  header: 'Room / Session',
                  render: (r) => (
                    <Link to={`/voice/${encodeURIComponent(r.sessionId)}`} className="font-mono text-xs text-indigo-600 hover:underline">
                      {r.room}
                    </Link>
                  ),
                },
                { key: 'outcome', header: 'Outcome', render: (r) => <StatusBadge value={r.outcome} /> },
                { key: 'turnCount', header: 'Turns', render: (r) => fmtInt(r.turnCount) },
                { key: 'bargeInCount', header: 'Barge-ins', render: (r) => fmtInt(r.bargeInCount) },
                { key: 'sttProvider', header: 'STT', render: (r) => r.sttProvider ?? <span className="text-slate-400">—</span> },
                { key: 'ttsProvider', header: 'TTS', render: (r) => r.ttsProvider ?? <span className="text-slate-400">—</span> },
                { key: 'startedAt', header: 'Started', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.startedAt)}</span> },
                { key: 'endedAt', header: 'Ended', render: (r) => <span className="whitespace-nowrap">{fmtTime(r.endedAt)}</span> },
              ]}
              rows={p.content}
              rowKey={(r) => r.sessionId}
              page={p.page}
              size={p.size}
              totalPages={p.totalPages}
              totalElements={p.totalElements}
              onPage={setPage}
            />
          )
        }
      </QueryState>
    </div>
  );
}
