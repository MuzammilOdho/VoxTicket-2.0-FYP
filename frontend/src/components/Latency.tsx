/** Latency value with a proportional bar. null -> "n/a". */
export function Latency({ ms, maxMs, label }: { ms: number | null | undefined; maxMs?: number; label?: string }) {
  if (ms === null || ms === undefined || Number.isNaN(ms)) {
    return <span className="text-xs text-ink-mute">n/a</span>;
  }
  const pct = maxMs && maxMs > 0 ? Math.min(100, (ms / maxMs) * 100) : 0;
  return (
    <span className="inline-flex min-w-[7rem] items-center gap-2">
      <span className="text-xs font-medium text-ink-dim">
        {label ? `${label}: ` : ''}
        {ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms.toFixed(0)}ms`}
      </span>
      {maxMs !== undefined && (
        <span className="h-1.5 w-16 overflow-hidden rounded bg-graphite">
          <span className="block h-full rounded bg-signal" style={{ width: `${pct}%` }} />
        </span>
      )}
    </span>
  );
}
