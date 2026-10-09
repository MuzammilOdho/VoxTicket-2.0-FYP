/** Product / How-it-works / Technology pages — Linear style. */
import { Page } from './chrome';
import { Badge, ButtonLink, Kicker } from '../ui/primitives';
import { Reveal } from '../ui/motion';

function DocShell({ kicker, title, intro, children }: {
  kicker: string;
  title: string;
  intro: string;
  children: React.ReactNode;
}) {
  return (
    <Page>
      <div className="mx-auto max-w-[1200px] px-6 py-16 md:py-24">
        <Reveal>
          <Kicker>{kicker}</Kicker>
          <h1 className="display-tight mt-4 max-w-3xl text-[40px] font-medium leading-[1.05] text-white md:text-[64px] md:leading-[1.0]">
            {title}
          </h1>
          <p className="mt-5 max-w-2xl text-[17px] leading-[1.6] text-fog">{intro}</p>
        </Reveal>
        <div className="mt-14 space-y-0">{children}</div>
      </div>
    </Page>
  );
}

function Block({ title, body, points, index }: {
  title: string;
  body: string;
  points?: string[];
  index: number;
}) {
  return (
    <Reveal delay={Math.min(index, 4) * 60}>
      <section className="grid gap-6 border-t border-graphite py-10 md:grid-cols-[240px_1fr] md:py-12">
        <h2 className="heading-tight text-[20px] font-medium leading-[1.33] text-white">{title}</h2>
        <div>
          <p className="max-w-2xl text-[16px] leading-[1.6] text-mist">{body}</p>
          {points && (
            <ul className="mt-5 space-y-2.5">
              {points.map((p) => (
                <li key={p} className="flex gap-3 text-[14px] leading-relaxed text-fog">
                  <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-signal" />
                  <span>{p}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>
    </Reveal>
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
        index={0}
        title="Voice-first conversations"
        body="Callers speak naturally and hear answers in under a second. The pipeline — LiveKit transport, AssemblyAI transcription, Cartesia synthesis — is tuned for interruption: talk over the agent and it stops, listens, and picks up the new thread."
        points={[
          'Streaming speech-to-text with Urdu, English, and Roman Urdu support',
          'Per-sentence TTS streaming: first audio before the full reply exists',
          'Adaptive interruption that distinguishes real barge-ins from backchannels',
        ]}
      />
      <Block
        index={1}
        title="Commerce-aware answers"
        body="VoxTicket is wired into the order system. It looks up orders, payments, shipments, and tickets — and answers from live state, not from a knowledge-base guess."
        points={[
          'Order status, items, and totals with ownership enforcement',
          'Shipment tracking with carrier state',
          'Payment status and refund history',
        ]}
      />
      <Block
        index={2}
        title="Procedures, not promises"
        body="Cancellations, returns, claims, and refunds run as deterministic state machines: the agent explains, the customer confirms explicitly, OTP verification fires where required, and every transition is audited."
        points={[
          'Explicit confirmation before any mutation',
          'OTP verification for sensitive actions',
          'Full audit trail of every procedure step',
        ]}
      />
      <Block
        index={3}
        title="Multilingual support"
        body="Language is detected per turn and the voice follows it. A caller can start in English, switch to Urdu mid-call, and the agent keeps up — no language menu, no restart."
      />
      <Reveal>
        <div className="flex gap-3 border-t border-graphite pt-10">
          <ButtonLink to="/demo" variant="primary" className="!px-6 !py-3">Try the demo</ButtonLink>
        </div>
      </Reveal>
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
      <Block index={0} title="1 · Capture" body="The caller's audio streams to the voice worker over LiveKit. Silero VAD detects speech; AssemblyAI Universal streaming produces the transcript; the audio turn detector decides the caller has finished." />
      <Block index={1} title="2 · Understand" body="The transcript is sent to the Java brain. A prompt guard screens for injection, the input is normalized, and a model router picks the right tier — fast models for simple turns, stronger ones for complex reasoning." />
      <Block index={2} title="3 · Act (under rules)" body="The agent calls tools: order lookups, RAG policy search, procedure controls. Tools that mutate state are gated — starting a cancellation is allowed, completing one requires the customer's explicit confirmation." />
      <Block index={3} title="4 · Speak" body="The reply streams back as server-sent events, sentence by sentence, into Cartesia TTS. The caller hears the first sentence while the rest generates. If the caller interrupts, the turn aborts cooperatively and the new turn takes over." />
      <Block index={4} title="5 · Record" body="Every turn emits a decision trace — model, tokens, tools, latency, procedure outcomes — to the operations console. Nothing the agent did is a black box." />
      <Reveal>
        <div className="flex flex-wrap gap-3 border-t border-graphite pt-10">
          <ButtonLink to="/technology" variant="ghost" className="!px-6 !py-3">Architecture details</ButtonLink>
          <ButtonLink to="/demo" variant="primary" className="!px-6 !py-3">Try the demo</ButtonLink>
        </div>
      </Reveal>
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
      <div className="g-border-soft overflow-hidden rounded-xl">
        {ARCH.map((a, i) => (
          <Reveal key={a.layer} delay={Math.min(i, 4) * 60}>
            <div className={`p-6 md:p-8 ${i > 0 ? 'border-t border-line/70' : ''}`}>
              <Badge tone="signal">{a.layer}</Badge>
              <ul className="mt-4 space-y-2.5">
                {a.items.map((item) => (
                  <li key={item} className="flex gap-3">
                    <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-smoke" />
                    <span className="font-mono text-[13px] leading-relaxed text-mist">{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          </Reveal>
        ))}
      </div>
      <Block
        index={5}
        title="Why this shape"
        body="Voice demands low latency, but support demands correctness. So the hot path is streaming and the dangerous path is deterministic: mutations only happen inside procedures with explicit confirmation, and the language model can never bypass them — it has no direct access to the database."
      />
      <Reveal>
        <div className="flex gap-3 border-t border-graphite pt-10">
          <ButtonLink to="/demo" variant="primary" className="!px-6 !py-3">Try the demo</ButtonLink>
        </div>
      </Reveal>
    </DocShell>
  );
}
