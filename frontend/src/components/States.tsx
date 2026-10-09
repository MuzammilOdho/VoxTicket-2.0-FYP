import { Loading, ErrorBox, Empty } from '../ui/primitives';

export function LoadingSpinner({ label = 'Loading…' }: { label?: string }) {
  return <Loading label={label} />;
}

export function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  return 'Something went wrong';
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const msg = errorMessage(error);
  const offline = msg.startsWith('Network error');
  return (
    <ErrorBox
      message={offline ? `${msg} — is the VoxTicket backend running on the expected host/port?` : msg}
      onRetry={onRetry}
    />
  );
}

export function EmptyState({ title = 'No data', hint }: { title?: string; hint?: string }) {
  return <Empty title={title} body={hint} />;
}

/** Small wrapper: spinner / error / empty / content. */
export function QueryState<T>({
  query,
  emptyTitle,
  children,
}: {
  query: { isLoading: boolean; isError: boolean; error: unknown; data: T | undefined; refetch: () => void };
  emptyTitle?: string;
  children: (data: T) => React.ReactNode;
}) {
  if (query.isLoading) return <LoadingSpinner />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  if (query.data === undefined || query.data === null) return <EmptyState title={emptyTitle} />;
  return <>{children(query.data)}</>;
}
