import { fmtTime } from './KpiCard';
import { StatusBadge } from './StatusBadge';
import type { TimelineEntry } from '../api/types';

function kindColor(kind: string): string {
  if (kind === 'MESSAGE') return 'border-sky-400';
  if (kind === 'EVENT') return 'border-amber-400';
  return 'border-indigo-400';
}

export function Timeline({ entries }: { entries: TimelineEntry[] }) {
  if (entries.length === 0) {
    return <div className="text-sm text-slate-400">No timeline entries.</div>;
  }
  return (
    <ol className="space-y-2">
      {entries.map((e, i) => (
        <li key={i} className={`rounded-r-lg border-l-4 bg-white p-3 shadow-sm ${kindColor(e.kind)}`}>
          <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500">
            <span className="font-mono text-slate-400">#{e.turnNumber}</span>
            <StatusBadge value={e.kind} />
            <span>{fmtTime(e.at)}</span>
            {e.traceId && <span className="font-mono text-[11px] text-slate-400">trace {e.traceId.slice(0, 12)}…</span>}
          </div>
          <div className="mt-1 text-sm font-medium text-slate-800">{e.label}</div>
          {e.detail && <div className="mt-0.5 whitespace-pre-wrap text-xs text-slate-500">{e.detail}</div>}
        </li>
      ))}
    </ol>
  );
}
