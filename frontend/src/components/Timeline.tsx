import { fmtTime } from './KpiCard';
import { StatusBadge } from './StatusBadge';
import type { TimelineEntry } from '../api/types';

function kindColor(kind: string): string {
  if (kind === 'MESSAGE') return 'border-info';
  if (kind === 'EVENT') return 'border-warn';
  return 'border-iris-violet';
}

export function Timeline({ entries }: { entries: TimelineEntry[] }) {
  if (entries.length === 0) {
    return <div className="text-sm text-ink-mute">No timeline entries.</div>;
  }
  return (
    <ol className="space-y-2">
      {entries.map((e, i) => (
        <li key={i} className={`rounded-xl border border-line/70 border-l-2 bg-raised p-3.5 ${kindColor(e.kind)}`}>
          <div className="flex flex-wrap items-center gap-2 text-xs text-ink-mute">
            <span className="font-mono text-ink-mute">#{e.turnNumber}</span>
            <StatusBadge value={e.kind} />
            <span>{fmtTime(e.at)}</span>
            {e.traceId && <span className="font-mono text-[11px] text-ink-mute">trace {e.traceId.slice(0, 12)}…</span>}
          </div>
          <div className="mt-1 text-sm font-medium text-ink">{e.label}</div>
          {e.detail && <div className="mt-0.5 whitespace-pre-wrap text-xs text-ink-mute">{e.detail}</div>}
        </li>
      ))}
    </ol>
  );
}
