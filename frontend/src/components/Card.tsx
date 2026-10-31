/** Shared shadcn page pieces — replaces components/Signal.tsx.
 *
 * The page system is now cards, not ruled sections: a white card with a
 * 24px radius, a 1px hairline border, the reference's whisper-quiet
 * shadow, and 20px padding. Titles are 18px/600, notes are 14px
 * mid-gray, fact labels are 12px uppercase mid-gray. The voice pipeline
 * strip is strictly achromatic (mid-gray / ink / ink-soft on a canvas
 * track); ember never appears here — a pipeline stage is not an error.
 * Pages keep their existing hooks and data — only presentation moves
 * here, so no page logic changes.
 */
import { SHADCN_TOKENS } from '../admin/tokens';

/** A card section: white card, title, one muted note, content. */
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
    <section id={id} className="mb-6 scroll-mt-6 rounded-[24px] border border-line bg-paper p-5 shadow-card">
      <h2 className="text-[18px] font-semibold text-ink">{title}</h2>
      {note && <p className="mb-4 mt-1 text-[14px] text-ink-mute">{note}</p>}
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
          <div className="text-[12px] font-medium uppercase tracking-[0.6px] text-ink-mute">{f.label}</div>
          <div className="mt-0.5 text-sm text-ink">{f.value}</div>
        </div>
      ))}
    </div>
  );
}

/**
 * One turn as a bar scaled to its end-to-end time. Segments are the three
 * measured stages: STT commit (light gray), brain to first token
 * (indigo — the stage that matters most, so it carries the accent),
 * TTS to first audio (mid-gray). Their sum is usually less than
 * end-to-end; the rest shows as the canvas track and is labelled
 * "remaining" in the legend, never folded into a stage. Numbers always
 * stay in the table next to the strip — the strip is for diagnosis at a
 * glance, not a replacement for the values.
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
      className="block h-2 w-32 overflow-hidden rounded-full bg-canvas"
      role="img"
      aria-label={`Pipeline: STT ${sttMs ?? 'n/a'}ms, brain ${brainMs ?? 'n/a'}ms, TTS ${ttsMs ?? 'n/a'}ms, end-to-end ${e2eMs}ms`}
    >
      <span className="flex h-full">
        {seg(sttMs, '#a3a3a3')}
        {seg(brainMs, SHADCN_TOKENS.accent)}
        {seg(ttsMs, SHADCN_TOKENS.midGray)}
      </span>
    </span>
  );
}

export function PipelineLegend() {
  const swatch = (colour: string, label: string, bordered = false) => (
    <span className="flex items-center gap-1.5">
      <span className={`inline-block h-2 w-4 rounded-full ${bordered ? 'border border-line' : ''}`} style={{ backgroundColor: colour }} />
      {label}
    </span>
  );
  return (
    <p className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-[12px] text-ink-mute">
      {swatch('#a3a3a3', 'STT commit')}
      {swatch(SHADCN_TOKENS.accent, 'Brain to first token')}
      {swatch(SHADCN_TOKENS.midGray, 'TTS to first audio')}
      {swatch(SHADCN_TOKENS.canvas, 'Remaining time to end of turn', true)}
    </p>
  );
}
