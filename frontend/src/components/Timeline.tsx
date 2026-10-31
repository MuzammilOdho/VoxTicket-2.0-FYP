import { useState } from 'react';
import { fmtTime } from './KpiCard';
import { StatusBadge } from './StatusBadge';
import type { TimelineEntry } from '../api/types';

function kindColor(kind: string): string {
  if (kind === 'MESSAGE') return 'border-info';
  if (kind === 'EVENT') return 'border-warn';
  return 'border-iris-violet';
}

export function Timeline({ entries }: { entries: TimelineEntry[] }) {
  const [kind, setKind] = useState<'ALL' | TimelineEntry['kind']>('ALL');
  if (entries.length === 0) {
    return <div className="text-sm text-ink-mute">No timeline entries.</div>;
  }
  const shown = kind === 'ALL' ? entries : entries.filter((e) => e.kind === kind);
  const kinds: ('ALL' | TimelineEntry['kind'])[] = ['ALL', 'MESSAGE', 'EVENT', 'TRACE'];
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-2" role="group" aria-label="Filter timeline by entry kind">
        {kinds.map((k) => (
          <button
            key={k}
            onClick={() => setKind(k)}
            aria-pressed={kind === k}
            className={`min-h-[36px] rounded-[18px] px-3 text-xs font-medium ${
              kind === k ? 'bg-signal/10 text-signal' : 'border border-line text-ink-dim hover:border-ink-mute hover:text-ink'
            }`}
          >
            {k === 'ALL' ? 'All' : k.charAt(0) + k.slice(1).toLowerCase()}
            <span className="ml-1.5 font-mono text-[11px] opacity-70">
              {k === 'ALL' ? entries.length : entries.filter((e) => e.kind === k).length}
            </span>
          </button>
        ))}
      </div>
      <ol className="divide-y divide-line-soft border-y border-line">
      {shown.map((e, i) => (
        <li key={i} className={`border-l-2 py-3 pl-3 ${kindColor(e.kind)}`}>
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
      {shown.length === 0 && (
        <p className="py-3 text-sm text-ink-mute">No {kind.toLowerCase()} entries in this conversation.</p>
      )}
    </div>
  );
}
