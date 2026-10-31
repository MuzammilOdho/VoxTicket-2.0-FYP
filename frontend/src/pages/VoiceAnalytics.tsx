import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useVoiceCalls, dateToInstant } from '../api/hooks';
import { fetchText } from '../api/client';
import { AdminPageHeader as PageHeader } from '../admin/shell';
import { EmptyState, QueryState } from '../components/States';
import { FilterBar, SelectInput, TextInput } from '../components/Filters';
import { DataTable } from '../components/Table';
import { Button } from '../components/Button';
import { StatusBadge } from '../components/StatusBadge';
import { fmtTime, fmtInt } from '../components/KpiCard';
import { Latency } from '../components/Latency';
import { downloadCsvText } from '../components/csv';

export function VoiceAnalytics() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [draft, setDraft] = useState({ outcome: '', from: '', to: '' });
  const [applied, setApplied] = useState({ outcome: '', from: '', to: '' });
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const q = useVoiceCalls(
    page,
    size,
    applied.outcome || undefined,
    applied.from || undefined,
    applied.to || undefined,
  );

  async function exportCsv() {
    setExporting(true);
    setExportError(null);
    try {
      const p = new URLSearchParams();
      if (applied.outcome) p.set('outcome', applied.outcome);
      const f = dateToInstant(applied.from || undefined, false);
      const t = dateToInstant(applied.to || undefined, true);
      if (f) p.set('from', f);
      if (t) p.set('to', t);
      const text = await fetchText(`/api/v1/admin/voice/calls/export${p.toString() ? `?${p}` : ''}`);
      downloadCsvText('voxticket-voice-calls.csv', text);
    } catch (e) {
      setExportError(e instanceof Error ? e.message : 'Export failed');
    } finally {
      setExporting(false);
    }
  }

  return (
    <div>
      <PageHeader title="Voice analytics" sub="LiveKit calls: outcomes, barge-ins, disconnects and stage latencies." />
      <FilterBar
        dateNote
        chips={[
          ...(applied.outcome ? [{ label: `Outcome: ${applied.outcome}`, onRemove: () => { setApplied({ ...applied, outcome: '' }); setDraft((d) => ({ ...d, outcome: '' })); setPage(0); } }] : []),
          ...(applied.from || applied.to ? [{ label: `Window: ${applied.from || '…'} → ${applied.to || '…'}`, onRemove: () => { setApplied({ ...applied, from: '', to: '' }); setDraft((d) => ({ ...d, from: '', to: '' })); setPage(0); } }] : []),
        ]}
        onReset={() => { setDraft({ outcome: '', from: '', to: '' }); setApplied({ outcome: '', from: '', to: '' }); setPage(0); }}>
        <SelectInput
          label="Outcome"
          value={draft.outcome}
          onChange={(v) => setDraft({ ...draft, outcome: v })}
          options={[{ value: 'COMPLETED', label: 'Completed' }, { value: 'ABORTED', label: 'Aborted' }]}
        />
        <TextInput label="From" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        <TextInput label="To" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        <Button onClick={() => { setApplied(draft); setPage(0); }}>Apply</Button>
      </FilterBar>
      <div className="mb-6 flex items-center gap-3">
        <Button variant="outline" onClick={exportCsv} disabled={exporting}>
          {exporting ? 'Exporting…' : 'Export CSV (all matching calls)'}
        </Button>
        {exportError && <span className="text-xs text-bad">{exportError}</span>}
      </div>
      <QueryState query={q} emptyTitle="No voice calls" skeleton>
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
                    <Link to={`/admin/voice/${encodeURIComponent(r.sessionId)}`} className="font-mono text-xs text-signal hover:underline hover:underline-offset-4">
                      {r.room}
                    </Link>
                  ),
                },
                { key: 'outcome', header: 'Outcome', render: (r) => <StatusBadge value={r.outcome} /> },
                { key: 'turnCount', header: 'Turns', render: (r) => fmtInt(r.turnCount) },
                { key: 'bargeInCount', header: 'Barge-ins', render: (r) => fmtInt(r.bargeInCount) },
                { key: 'avgE2eMs', header: 'Mean end-to-end', render: (r) => <Latency ms={r.avgE2eMs} /> },
                { key: 'avgBrainTtftMs', header: 'Mean brain TTFT', render: (r) => <Latency ms={r.avgBrainTtftMs} /> },
                { key: 'sttProvider', header: 'STT', render: (r) => r.sttProvider ?? <span className="text-ink-mute">—</span> },
                { key: 'ttsProvider', header: 'TTS', render: (r) => r.ttsProvider ?? <span className="text-ink-mute">—</span> },
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
              onSizeChange={(n) => { setSize(n); setPage(0); }}
            />
          )
        }
      </QueryState>
    </div>
  );
}
