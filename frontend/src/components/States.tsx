import { Loading, ErrorBox, Empty } from '../ui/primitives';

export function LoadingSpinner({ label = 'Loading…' }: { label?: string }) {
  return <Loading label={label} />;
}

/** Operator-language error text: raw backend/network text is not guidance. */
export function errorMessage(e: unknown): string {
  const msg = e instanceof Error ? e.message : 'Something went wrong';
  if (msg.startsWith('Network error')) return `${msg} — is the VoxTicket backend running on the expected host/port?`;
  if (msg.includes('404') || msg.toLowerCase().includes('not found')) return 'Record not found — it may have been removed, or the link is stale.';
  return msg;
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return <ErrorBox message={errorMessage(error)} onRetry={onRetry} />;
}

export function EmptyState({ title = 'No data', hint }: { title?: string; hint?: string }) {
  return <Empty title={title} body={hint} />;
}

/** Skeleton rows for list pages: shape without the spinner flash. */
export function SkeletonRows({ rows = 5 }: { rows?: number }) {
  return (
    <div role="status" aria-label="Loading" className="border-y border-line">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex gap-4 border-b border-line-soft px-3 py-3 last:border-0">
          <span className="h-3.5 w-40 bg-raised-2" />
          <span className="h-3.5 w-24 bg-raised-2" />
          <span className="h-3.5 w-24 bg-raised-2" />
          <span className="hidden h-3.5 flex-1 bg-raised-2 md:block" />
        </div>
      ))}
    </div>
  );
}

/**
 * Small wrapper: skeleton/spinner / error / empty / content.
 *
 * Stage 5 behaviour: first load shows a skeleton (list pages) or spinner
 * (detail/aggregate pages); a refetch with data already on screen keeps
 * that data visible and shows a quiet "Updating…" line instead of
 * blanking the page. `query.isFetching` comes from React Query; pages
 * pass their query object straight through, so nothing else changes.
 */
export function QueryState<T>({
  query,
  emptyTitle,
  skeleton = false,
  children,
}: {
  query: {
    isLoading: boolean;
    isFetching?: boolean;
    isError: boolean;
    error: unknown;
    data: T | undefined;
    refetch: () => void;
  };
  emptyTitle?: string;
  skeleton?: boolean;
  children: (data: T) => React.ReactNode;
}) {
  if (query.isLoading) return skeleton ? <SkeletonRows /> : <LoadingSpinner />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  if (query.data === undefined || query.data === null) return <EmptyState title={emptyTitle} />;
  return (
    <>
      {query.isFetching && (
        <p role="status" className="mb-3 text-xs text-ink-mute">
          Updating…
        </p>
      )}
      {children(query.data)}
    </>
  );
}
