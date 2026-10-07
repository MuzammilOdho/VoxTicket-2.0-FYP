import type { ReactNode } from 'react';

export interface Column<T> {
  key: string;
  header: string;
  render?: (row: T) => ReactNode;
  className?: string;
}

export function getCell<T>(row: T, key: string): ReactNode {
  if (typeof row === 'object' && row !== null && key in row) {
    const v = (row as Record<string, unknown>)[key];
    if (v === null || v === undefined) return <span className="text-ink-mute">n/a</span>;
    if (typeof v === 'boolean') return v ? 'yes' : 'no';
    return String(v);
  }
  return '';
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  page,
  size,
  totalPages,
  totalElements,
  onPage,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T, i: number) => string;
  page?: number;
  size?: number;
  totalPages?: number;
  totalElements?: number;
  onPage?: (page: number) => void;
}) {
  return (
    <div>
      <div className="overflow-x-auto border border-line bg-raised">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="border-b border-line">
              {columns.map((c) => (
                <th key={c.key} className="px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-mute">
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={rowKey(row, i)} className="border-b border-line-soft last:border-0 hover:bg-raised-2">
                {columns.map((c) => (
                  <td key={c.key} className={`px-3 py-2 align-top text-ink-dim ${c.className ?? ''}`}>
                    {c.render ? c.render(row) : getCell(row, c.key)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {onPage !== undefined && totalPages !== undefined && (
        <div className="mt-3 flex items-center justify-between text-sm text-ink-mute">
          <span className="font-mono text-xs">
            {totalElements !== undefined && size !== undefined && (
              <>
                {Math.min((page ?? 0) * size + 1, totalElements)}-
                {Math.min(((page ?? 0) + 1) * size, totalElements)} / {totalElements.toLocaleString()}
              </>
            )}
          </span>
          <div className="flex items-center gap-2">
            <button
              disabled={(page ?? 0) <= 0}
              onClick={() => onPage((page ?? 0) - 1)}
              className="border border-line px-3 py-1 text-ink-dim hover:border-ink-mute hover:text-ink disabled:opacity-40"
            >
              Prev
            </button>
            <span className="px-2 py-1 font-mono text-xs">
              {(page ?? 0) + 1} / {Math.max(1, totalPages)}
            </span>
            <button
              disabled={(page ?? 0) + 1 >= totalPages}
              onClick={() => onPage((page ?? 0) + 1)}
              className="border border-line px-3 py-1 text-ink-dim hover:border-ink-mute hover:text-ink disabled:opacity-40"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/** Generic table for flat Record<string,unknown> rows: columns derived from the first row. */
export function GenericTable({ rows, rowKey }: { rows: Record<string, unknown>[]; rowKey: (row: Record<string, unknown>, i: number) => string }) {
  if (rows.length === 0) return null;
  const cols = Object.keys(rows[0]);
  return (
    <DataTable
      columns={cols.map((k) => ({ key: k, header: k }))}
      rows={rows}
      rowKey={rowKey}
    />
  );
}
