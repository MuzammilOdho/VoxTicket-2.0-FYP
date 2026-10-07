/** Product / How-it-works / Technology pages. */
import { Page } from './chrome';
import { Badge, ButtonLink } from '../ui/primitives';

function DocShell({ kicker, title, intro, children }: { kicker: string; title: string; intro: string; children: React.ReactNode }) {
  return (
    <Page>
      <div className="mx-auto max-w-4xl px-5 py-14 md:py-20">
        <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-signal">{kicker}</div>
        <h1 className="mt-3 text-4xl font-semibold tracking-tight text-ink">{title}</h1>
        <p className="mt-4 text-lg leading-relaxed text-ink-dim">{intro}</p>
        <div className="mt-10 space-y-10">{children}</div>
      </div>
    </Page>
  );
}

function Block({ title, body, points }: { title: string; body: string; points?: string[] }) {
  return (
    <section className="border-t border-line pt-6">
      <h2 className="text-xl font-semibold text-ink">{title}</h2>
      <p className="mt-2 text-[15px] leading-relaxed text-ink-dim">{body}</p>
      {points && (
        <ul className="mt-4 space-y-2">
          {points.map((p) => (
            <li key={p} className="flex gap-3 text-sm text-ink-dim">
              <span className="mt-1.5 h-1.5 w-1.5 shrink-0 bg-signal" />
              <span>{p}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function Product() {
  return (
    <DocShell
      kicker="Product"
      title="What VoxTicket does"
      intro="One agent across voice and chat that resolves the support workload e-commerce teams actually drown in: where is my order, cancel it, return it, refund it, fix my payment."
    >
      <Block
        title="Voice-first conversations"
        body="Callers speak naturally and hear answers in under a second. The pipeline — LiveKit transport, AssemblyAI transcription, Cartesia synthesis — is tuned for interruption: talk over the agent and it stops, listens, and picks up the new thread."
        points={[
          'Streaming speech-to-text with Urdu, English, and Roman Urdu support',
          'Per-sentence TTS streaming: first audio before the full reply exists',
          'Adaptive interruption that distinguishes real barge-ins from backchannels',
        ]}
      />
      <Block
        title="Commerce-aware answers"
        body="VoxTicket is wired into the order system. It looks up orders, payments, shipments, and tickets — and answers from live state, not from a knowledge-base guess."
        points={[
          'Order status, items, and totals with ownership enforcement',
          'Shipment tracking with carrier state',
          'Payment status and refund history',
        ]}
      />
      <Block
        title="Procedures, not promises"
        body="Cancellations, returns, claims, and refunds run as deterministic state machines: the agent explains, the customer confirms explicitly, OTP verification fires where required, and every transition is audited."
        points={[
          'Explicit confirmation before any mutation',
          'OTP verification for sensitive actions',
          'Full audit trail of every procedure step',
        ]}
      />
      <Block
        title="Multilingual support"
        body="Language is detected per turn and the voice follows it. A caller can start in English, switch to Urdu mid-call, and the agent keeps up — no language menu, no restart."
      />
      <div className="flex gap-3 pt-4">
        <ButtonLink to="/demo" variant="primary">Try the demo</ButtonLink>
      </div>
    </DocShell>
  );
}

export function HowItWorks() {
  return (
    <DocShell
      kicker="How it works"
      title="From speech to resolution"
      intro="A turn through the system: what happens between the caller finishing a sentence and hearing the answer."
    >
      <Block
        title="1 · Capture"
        body="The caller's audio streams to the voice worker over LiveKit. Silero VAD detects speech; AssemblyAI Universal streaming produces the transcript; the audio turn detector decides the caller has finished."
      />
      <Block
        title="2 · Understand"
        body="The transcript is sent to the Java brain. A prompt guard screens for injection, the input is normalized, and a model router picks the right tier — fast models for simple turns, stronger ones for complex reasoning."
      />
      <Block
        title="3 · Act (under rules)"
        body="The agent calls tools: order lookups, RAG policy search, procedure controls. Tools that mutate state are gated — starting a cancellation is allowed, completing one requires the customer's explicit confirmation."
      />
      <Block
        title="4 · Speak"
        body="The reply streams back as server-sent events, sentence by sentence, into Cartesia TTS. The caller hears the first sentence while the rest generates. If the caller interrupts, the turn aborts cooperatively and the new turn takes over."
      />
      <Block
        title="5 · Record"
        body="Every turn emits a decision trace — model, tokens, tools, latency, procedure outcomes — to the operations console. Nothing the agent did is a black box."
      />
      <div className="flex gap-3 pt-4">
        <ButtonLink to="/technology" variant="ghost">Architecture details</ButtonLink>
        <ButtonLink to="/demo" variant="primary">Try the demo</ButtonLink>
      </div>
    </DocShell>
  );
}

const ARCH = [
  {
    layer: 'Transport',
    items: ['LiveKit WebRTC rooms for realtime audio', 'Server-sent events from Java to the voice worker', 'HTTP for chat turns and admin APIs'],
  },
  {
    layer: 'Speech',
    items: ['AssemblyAI Universal streaming STT (EN/UR, code-switching)', 'Cartesia Sonic neural TTS with per-reply voice switching', 'Silero VAD + adaptive interruption handling'],
  },
  {
    layer: 'Brain (Java / Spring Boot)',
    items: ['Conversation runtime: one state machine for voice and chat', 'Deterministic procedures: cancel, return, claim, refund', 'Prompt guard, ownership checks, OTP verification', 'RAG over pgvector for policy knowledge'],
  },
  {
    layer: 'Data',
    items: ['PostgreSQL system of record: customers, orders, payments, shipments', 'Async audit writer: every message, event, and turn trace', 'Flyway migrations; deterministic demo seed dataset'],
  },
  {
    layer: 'Operations',
    items: ['Realtime console: conversations, voice analytics, AI analytics', 'Per-turn decision traces with latency breakdowns', 'Health, evaluation, and audit surfaces'],
  },
];

export function Technology() {
  return (
    <DocShell
      kicker="Technology"
      title="Architecture"
      intro="A realtime voice pipeline on a deterministic Java core. The AI proposes; the system disposes."
    >
      <div className="space-y-px border border-line bg-line">
        {ARCH.map((a) => (
          <div key={a.layer} className="bg-raised p-6">
            <div className="flex items-center gap-3">
              <Badge tone="signal">{a.layer}</Badge>
            </div>
            <ul className="mt-4 space-y-2">
              {a.items.map((i) => (
                <li key={i} className="flex gap-3 text-sm text-ink-dim">
                  <span className="mt-1.5 h-1.5 w-1.5 shrink-0 bg-line" />
                  <span className="font-mono text-[13px]">{i}</span>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      <Block
        title="Why this shape"
        body="Voice demands low latency, but support demands correctness. So the hot path is streaming and the dangerous path is deterministic: mutations only happen inside procedures with explicit confirmation, and the language model can never bypass them — it has no direct access to the database."
      />
      <div className="flex gap-3 pt-4">
        <ButtonLink to="/demo" variant="primary">Try the demo</ButtonLink>
      </div>
    </DocShell>
  );
}
