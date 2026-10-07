export function LoadingSpinner({ label = 'Loading...' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-10 text-slate-500">
      <span className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-slate-300 border-t-indigo-600" />
      <span className="text-sm">{label}</span>
    </div>
  );
}

export function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  return 'Something went wrong';
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const msg = errorMessage(error);
  const offline = msg.startsWith('Network error');
  return (
    <div className="mx-auto my-10 max-w-md rounded-xl border border-red-200 bg-red-50 p-6 text-center">
      <div className="text-lg font-semibold text-red-800">{offline ? 'Backend unreachable' : 'Failed to load'}</div>
      <p className="mt-1 text-sm text-red-700">{msg}</p>
      {offline && <p className="mt-2 text-xs text-red-600">Is the VoxTicket backend running on the expected host/port?</p>}
      {onRetry && (
        <button
          onClick={onRetry}
          className="mt-4 rounded-lg bg-red-700 px-4 py-2 text-sm font-medium text-white hover:bg-red-800"
        >
          Retry
        </button>
      )}
    </div>
  );
}

export function EmptyState({ title = 'No data', hint }: { title?: string; hint?: string }) {
  return (
    <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-8 text-center">
      <div className="text-sm font-medium text-slate-600">{title}</div>
      {hint && <div className="mt-1 text-xs text-slate-400">{hint}</div>}
    </div>
  );
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
