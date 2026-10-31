/** Phase 1 (admin rework): small CSV download helpers. No dependencies. */

function csvCell(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return '';
  const s = String(value);
  if (s.includes(',') || s.includes('"') || s.includes('\n') || s.includes('\r')) {
    return `"${s.replace(/"/g, '""')}"`;
  }
  return s;
}

function downloadBlob(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Build a CSV from header names + row value arrays and download it. */
export function downloadCsv(
  filename: string,
  headers: string[],
  rows: (string | number | null | undefined)[][],
) {
  const lines = [headers.map(csvCell).join(',')];
  for (const row of rows) {
    lines.push(row.map(csvCell).join(','));
  }
  downloadBlob(filename, lines.join('\n') + '\n', 'text/csv;charset=utf-8');
}

/** Download server-provided CSV text (voice-call export endpoint). */
export function downloadCsvText(filename: string, text: string) {
  downloadBlob(filename, text, 'text/csv;charset=utf-8');
}
