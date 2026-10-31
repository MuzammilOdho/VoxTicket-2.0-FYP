/** Shared Signal page pieces, Phase 5 (admin rework).
 *
 * Signal replaces the Ledger system (Phases 3–4) with a light data
 * terminal: sections divided by thick black rules instead of cards or
 * hairlines, sharp corners, Archivo display type, JetBrains Mono for
 * labels and numbers, electric indigo as the single action colour.
 * These three pieces are the whole page system: a ruled section, a
 * label/value fact grid for detail headers, and the voice pipeline
 * strip. Pages keep their existing hooks and data — only presentation
 * moves here, so no page logic changes.
 */

/** A section: thick black rule, Archivo title, one muted note, content. */
export function Section({
  title,
  note,
  children,
  id,
}: {
  title: string;
  note?: React.ReactNode;
  children: React.ReactNode;
  id?: string;
}) {
  return (
    <section id={id} className="mt-9 scroll-mt-6">
      <h2 className="border-t-2 border-ink pt-3 font-serif text-[21px] font-bold text-ink">{title}</h2>
      {note && <p className="mb-3 mt-1 text-[13.5px] text-ink-mute">{note}</p>}
      {children}
    </section>
  );
}

/** Label/value pairs for detail headers (session, call, aggregates). */
export function FactGrid({ items }: { items: { label: string; value: React.ReactNode }[] }) {
  return (
    <div className="grid grid-cols-2 gap-x-6 gap-y-3 md:grid-cols-4">
      {items.map((f) => (
        <div key={f.label}>
          <div className="text-[12px] font-medium text-ink-mute">{f.label}</div>
          <div className="mt-0.5 text-sm text-ink">{f.value}</div>
        </div>
      ))}
    </div>
  );
}

/**
 * One turn as a bar scaled to its end-to-end time. Segments are the three
 * measured stages: STT commit (grey), brain to first token (indigo), TTS
 * to first audio (ink). Their sum is usually less than end-to-end; the
 * rest shows as the pale track and is labelled "remaining" in the legend,
 * never folded into a stage. Numbers always stay in the table next to the
 * strip — the strip is for diagnosis at a glance, not a replacement for
 * the values.
 */
export function PipelineStrip({
  sttMs,
  brainMs,
  ttsMs,
  e2eMs,
}: {
  sttMs: number | null | undefined;
  brainMs: number | null | undefined;
  ttsMs: number | null | undefined;
  e2eMs: number | null | undefined;
}) {
  if (e2eMs === null || e2eMs === undefined || e2eMs <= 0) {
    return <span className="text-xs text-ink-mute">n/a</span>;
  }
  const seg = (v: number | null | undefined, colour: string) => {
    if (v === null || v === undefined || v <= 0) return null;
    return (
      <span
        className="block h-full"
        style={{ width: `${Math.max(2, (v / e2eMs) * 100)}%`, backgroundColor: colour }}
      />
    );
  };
  return (
    <span
      className="block h-2 w-32 overflow-hidden bg-line-soft"
      role="img"
      aria-label={`Pipeline: STT ${sttMs ?? 'n/a'}ms, brain ${brainMs ?? 'n/a'}ms, TTS ${ttsMs ?? 'n/a'}ms, end-to-end ${e2eMs}ms`}
    >
      <span className="flex h-full">
        {seg(sttMs, '#9aa7b8')}
        {seg(brainMs, '#2545d3')}
        {seg(ttsMs, '#101418')}
      </span>
    </span>
  );
}

export function PipelineLegend() {
  const swatch = (colour: string, label: string) => (
    <span className="flex items-center gap-1.5">
      <span className="inline-block h-2 w-4" style={{ backgroundColor: colour }} />
      {label}
    </span>
  );
  return (
    <p className="mb-3 flex flex-wrap gap-x-4 gap-y-1 font-mono text-[11px] text-ink-mute">
      {swatch('#9aa7b8', 'STT commit')}
      {swatch('#2545d3', 'Brain to first token')}
      {swatch('#101418', 'TTS to first audio')}
      {swatch('#edeff2', 'Remaining time to end of turn')}
    </p>
  );
}
