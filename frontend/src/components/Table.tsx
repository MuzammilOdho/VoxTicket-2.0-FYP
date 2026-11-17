import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Button } from './Button';

export interface Column<T> {
  key: string;
  header: string;
  render?: (row: T) => ReactNode;
  className?: string;
  /** Mark a column sortable. Sorting is client-side and therefore only
      wired on tables whose rows are the complete dataset (turns, tools,
      cost rows). Paged lists do NOT get sort buttons: sorting one loaded
      page would pretend to sort the whole dataset. Server-side sort is
      the one deferred backend item (see delivery notes). */
  sortable?: boolean;
  sortValue?: (row: T) => string | number | null | undefined;
}

export function getCell<T>(row: T, key: string): ReactNode {
  if (typeof row === 'object' && row !== null && key in row) {
    const v = (row as Record<string, unknown>)[key];
    if (v === null || v === undefined) return <span className="text-ink-mute">n/a</span>;
    if (typeof v === 'boolean') return v ? 'yes' : 'no';
    if (typeof v === 'object') return <span className="text-ink-mute">—</span>;
    return String(v);
  }
  return '';
}

function compareValues(a: string | number | null | undefined, b: string | number | null | undefined): number {
  if (a === null || a === undefined) return 1;
  if (b === null || b === undefined) return -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  return String(a).localeCompare(String(b));
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
  onSizeChange,
  caption,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T, i: number) => string;
  page?: number;
  size?: number;
  totalPages?: number;
  totalElements?: number;
  onPage?: (page: number) => void;
  onSizeChange?: (size: number) => void;
  caption?: string;
}) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const sortedRows = useMemo(() => {
    if (!sortKey) return rows;
    const col = columns.find((c) => c.key === sortKey);
    if (!col?.sortValue) return rows;
    const sorted = [...rows].sort((a, b) => compareValues(col.sortValue!(a), col.sortValue!(b)));
    return sortDir === 'asc' ? sorted : sorted.reverse();
  }, [rows, sortKey, sortDir, columns]);

  function toggleSort(col: Column<T>) {
    if (!col.sortable || !col.sortValue) return;
    if (sortKey === col.key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(col.key);
      setSortDir('asc');
    }
  }

  return (
    <div>
      <div className="overflow-x-auto rounded-xl border border-line bg-paper shadow-card">
        <table className="min-w-full text-[14px]">
          {caption && <caption className="py-2 text-left text-xs text-ink-mute">{caption}</caption>}
          <thead>
            <tr className="border-b border-line">
              {columns.map((c) => {
                const sortable = c.sortable && c.sortValue;
                const active = sortKey === c.key;
                return (
                  <th
                    key={c.key}
                    scope="col"
                    aria-sort={active ? (sortDir === 'asc' ? 'ascending' : 'descending') : undefined}
                    className="bg-paper px-3 py-2 text-left text-[12px] font-medium uppercase tracking-[0.6px] text-ink-mute md:sticky md:top-0"
                  >
                    {sortable ? (
                      <button
                        onClick={() => toggleSort(c)}
                        aria-label={`Sort by ${c.header} ${active && sortDir === 'asc' ? 'descending' : 'ascending'}`}
                        className="inline-flex min-h-[36px] items-center gap-1 text-left hover:text-ink"
                      >
                        {c.header}
                        <span aria-hidden>{active ? (sortDir === 'asc' ? '↑' : '↓') : '↕'}</span>
                      </button>
                    ) : (
                      c.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {sortedRows.map((row, i) => (
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
        <div className="mt-3 flex flex-wrap items-center justify-between gap-3 text-sm text-ink-mute">
          <span className="font-mono text-xs">
            {totalElements !== undefined && size !== undefined && (
              <>
                {Math.min((page ?? 0) * size + 1, totalElements)}-
                {Math.min(((page ?? 0) + 1) * size, totalElements)} / {totalElements.toLocaleString()}
              </>
            )}
          </span>
          <div className="flex flex-wrap items-center gap-2">
            {onSizeChange && (
              <label className="flex items-center gap-2 text-xs">
                <span>Per page</span>
                <select
                  value={size ?? 20}
                  onChange={(e) => onSizeChange(Number(e.target.value))}
                  aria-label="Rows per page"
                  className="min-h-[36px] rounded-md border border-line bg-paper px-2 text-sm text-ink outline-none focus:border-brand focus:ring-2 focus:ring-brand/15"
                >
                  {[20, 50, 100].map((n) => (
                    <option key={n} value={n}>{n}</option>
                  ))}
                </select>
              </label>
            )}
            <Button variant="outline" disabled={(page ?? 0) <= 0} onClick={() => onPage(0)}>
              First
            </Button>
            <Button variant="outline" disabled={(page ?? 0) <= 0} onClick={() => onPage((page ?? 0) - 1)}>
              Prev
            </Button>
            <span className="px-2 py-1 font-mono text-xs">
              {(page ?? 0) + 1} / {Math.max(1, totalPages)}
            </span>
            <Button variant="outline" disabled={(page ?? 0) + 1 >= totalPages} onClick={() => onPage((page ?? 0) + 1)}>
              Next
            </Button>
            <Button variant="outline" disabled={(page ?? 0) + 1 >= totalPages} onClick={() => onPage(totalPages - 1)}>
              Last
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

/** Generic table for flat Record<string,unknown> rows: columns derived from the first row.
 * Kept only for ad-hoc use. Operations pages use explicit columns (see
 * pages/Operations.tsx) so a missing field can never drop a column. */
export function GenericTable<T extends Record<string, unknown>>({ rows, rowKey }: { rows: T[]; rowKey: (row: T, i: number) => string }) {
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
