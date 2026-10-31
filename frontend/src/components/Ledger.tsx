/** Shared Ledger page pieces, Phase 4 (admin rework).
 *
 * Phase 3 restyled the shell and the Dashboard but left every other page
 * in the old card-kit layout (rounded boxes, tinted bubbles, pill tabs).
 * These three pieces are the whole page system now: a ruled section, a
 * label/value fact grid for detail headers, and the voice pipeline strip
 * from the approved Ledger direction. Pages keep their existing hooks
 * and data — only presentation moves here, so no page logic changes.
 */

/** A ruled section: serif title, one muted note, then content. */
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
      <h2 className="font-serif text-[21px] font-bold text-ink">{title}</h2>
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
          <div className="text-xs text-ink-mute">{f.label}</div>
          <div className="mt-0.5 text-sm text-ink">{f.value}</div>
        </div>
      ))}
    </div>
  );
}

/**
 * One turn as a bar scaled to its end-to-end time — the Ledger direction's
 * memorable element. Segments are the three measured stages: STT commit
 * (grey), brain to first token (teal), TTS to first audio (ink). Their
 * sum is usually less than end-to-end; the rest shows as the pale track
 * and is labelled "remaining" in the legend, never folded into a stage.
 * Numbers always stay in the table next to the strip — the strip is for
 * diagnosis at a glance, not a replacement for the values.
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
      className="block h-2.5 w-32 overflow-hidden rounded-sm bg-line-soft"
      role="img"
      aria-label={`Pipeline: STT ${sttMs ?? 'n/a'}ms, brain ${brainMs ?? 'n/a'}ms, TTS ${ttsMs ?? 'n/a'}ms, end-to-end ${e2eMs}ms`}
    >
      <span className="flex h-full">
        {seg(sttMs, '#c9c5bb')}
        {seg(brainMs, '#0f766e')}
        {seg(ttsMs, '#1a2422')}
      </span>
    </span>
  );
}

export function PipelineLegend() {
  const swatch = (colour: string, label: string) => (
    <span className="flex items-center gap-1.5">
      <span className="inline-block h-2.5 w-4 rounded-sm" style={{ backgroundColor: colour }} />
      {label}
    </span>
  );
  return (
    <p className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-mute">
      {swatch('#c9c5bb', 'STT commit')}
      {swatch('#0f766e', 'Brain to first token')}
      {swatch('#1a2422', 'TTS to first audio')}
      {swatch('#e9e6de', 'Remaining time to end of turn')}
    </p>
  );
}
