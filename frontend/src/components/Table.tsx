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
    if (v === null || v === undefined) return <span className="text-slate-400">n/a</span>;
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
      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50">
            <tr>
              {columns.map((c) => (
                <th key={c.key} className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wide text-slate-500">
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((row, i) => (
              <tr key={rowKey(row, i)} className="hover:bg-slate-50">
                {columns.map((c) => (
                  <td key={c.key} className={`px-3 py-2 align-top text-slate-700 ${c.className ?? ''}`}>
                    {c.render ? c.render(row) : getCell(row, c.key)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {onPage !== undefined && totalPages !== undefined && (
        <div className="mt-3 flex items-center justify-between text-sm text-slate-600">
          <span>
            {totalElements !== undefined && size !== undefined && (
              <>
                Showing {Math.min((page ?? 0) * size + 1, totalElements)}-
                {Math.min(((page ?? 0) + 1) * size, totalElements)} of {totalElements.toLocaleString()}
              </>
            )}
          </span>
          <div className="flex gap-2">
            <button
              disabled={(page ?? 0) <= 0}
              onClick={() => onPage((page ?? 0) - 1)}
              className="rounded-lg border border-slate-300 px-3 py-1 disabled:opacity-40"
            >
              Prev
            </button>
            <span className="px-2 py-1">
              {(page ?? 0) + 1} / {Math.max(1, totalPages)}
            </span>
            <button
              disabled={(page ?? 0) + 1 >= totalPages}
              onClick={() => onPage((page ?? 0) + 1)}
              className="rounded-lg border border-slate-300 px-3 py-1 disabled:opacity-40"
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
