/** VoxTicket landing — ElevenLabs system (DESIGN-ELEVENLABS.md).
 * Asymmetric editorial hero: 48px whisper-weight headline left, body
 * copy and pill buttons right. Below it, one large taupe feature
 * panel carries the audio sphere — the reference's signature product
 * visual (violet + orange radial blend, used only here and in the
 * live voice state) — beside the reconstructed live call. Sections
 * alternate eggshell / taupe bands at 96px+ gaps, max width 1280px.
 * All copy is the baseline copy, preserved. */
import { Link } from 'react-router-dom';
import { Page } from './chrome';
import { Badge, ButtonLink, Kicker } from '../ui/primitives';
import { Reveal } from '../ui/motion';

/* ---------------- audio sphere ----------------
   The product visual: a 200px soft radial blend of violet, orange
   and pink with a white play overlay. No hard edges. */

function AudioSphere({ size = 200 }: { size?: number }) {
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }} aria-hidden>
      <div className="audio-sphere audio-sphere-live h-full w-full rounded-full" />
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-paper shadow-card">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" className="ml-0.5 text-ink" aria-hidden>
            <path d="M8 5.5v13l11-6.5-11-6.5z" />
          </svg>
        </span>
      </div>
    </div>
  );
}

/* ---------------- live-call reconstruction ---------------- */

function Waveform() {
  const bars = [34, 58, 44, 72, 90, 64, 80, 52, 68, 88, 46, 60, 74, 40, 56, 70, 48, 62, 36, 54, 78, 42, 66, 50];
  return (
    <div className="flex h-10 items-center gap-[3px]" aria-hidden>
      {bars.map((h, i) => (
        <span
          key={i}
          className="vq-bar w-[3px] rounded-full bg-brand"
          style={{ height: `${h}%`, animationDelay: `${(i % 8) * 0.12}s` }}
        />
      ))}
    </div>
  );
}

function ProductCard() {
  return (
    <div className="relative overflow-hidden rounded-2xl border border-line bg-paper shadow-card">
      {/* window chrome */}
      <div className="flex items-center gap-2 border-b border-line px-5 py-3.5">
        <span className="h-2.5 w-2.5 rounded-full bg-stone" />
        <span className="h-2.5 w-2.5 rounded-full bg-stone" />
        <span className="h-2.5 w-2.5 rounded-full bg-stone" />
        <span className="ml-3 font-mono text-[11px] text-smoke">voxticket — live call</span>
        <span className="ml-auto flex items-center gap-1.5 font-mono text-[11px] text-ok">
          <span className="vq-pulse inline-block h-1.5 w-1.5 rounded-full bg-ok" />
          CONNECTED
        </span>
      </div>

      <div className="grid text-left md:grid-cols-[1.5fr_1fr]">
        {/* transcript */}
        <div className="space-y-4 p-5 md:p-6">
          <div className="flex justify-end">
            <div className="max-w-[85%] rounded-xl rounded-br-md bg-brand px-4 py-2.5 text-[14px] leading-relaxed text-white">
              Where is my order? It&apos;s been a week.
            </div>
          </div>
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-xl rounded-bl-md border border-line bg-paper px-4 py-2.5 text-[14px] leading-relaxed text-ink">
              Order <span className="font-mono text-[13px]">VT-88412</span> shipped yesterday —
              it&apos;s with the courier in Karachi, arriving Thursday.
            </div>
          </div>
          <div className="flex justify-end">
            <div className="max-w-[85%] rounded-xl rounded-br-md bg-brand px-4 py-2.5 text-[14px] leading-relaxed text-white">
              Actually, cancel it instead.
            </div>
          </div>
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-xl rounded-bl-md border border-line bg-surface px-4 py-2.5 text-[14px] leading-relaxed text-ink">
              I can cancel <span className="font-mono text-[13px]">VT-88412</span> with a full refund.
              Shall I go ahead?
              <div className="mt-2.5 flex gap-2">
                <span className="rounded-full bg-brand px-3 py-1 text-[12px] font-medium text-white">Confirm</span>
                <span className="rounded-full border border-line bg-paper px-3 py-1 text-[12px] text-ink-dim">Keep order</span>
              </div>
            </div>
          </div>
          <div className="border-t border-line pt-4">
            <Waveform />
            <div className="mt-2 flex items-center justify-between font-mono text-[11px] text-smoke">
              <span>listening…</span>
              <span>turn latency 840ms</span>
            </div>
          </div>
        </div>

        {/* decision trace */}
        <div className="border-t border-line bg-surface p-5 md:border-l md:border-t-0 md:p-6">
          <div className="font-mono text-[10px] text-smoke">decision trace</div>
          <div className="mt-4 space-y-3 font-mono text-[12px]">
            {[
              ['intent', 'cancel_order', 'font-medium text-ink'],
              ['order', 'VT-88412 · owned ✓', 'text-ink'],
              ['policy', 'cancel ≤ 24h · refundable', 'text-ink'],
              ['guard', 'confirmation required', 'text-warn'],
              ['model', 'tier-2 · 840ms', 'text-smoke'],
            ].map(([k, v, c]) => (
              <div key={k} className="flex items-baseline justify-between gap-3">
                <span className="text-smoke">{k}</span>
                <span className={c}>{v}</span>
              </div>
            ))}
          </div>
          <div className="mt-5 border-t border-line pt-4">
            <div className="font-mono text-[10px] text-smoke">audit</div>
            <div className="mt-2 font-mono text-[12px] text-ok">● every step recorded</div>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ---------------- hero ---------------- */

function Hero() {
  return (
    <section>
      <div className="mx-auto max-w-[1280px] px-6 pb-16 pt-20 md:pb-24 md:pt-28">
        <div className="grid items-end gap-10 md:grid-cols-[1.15fr_1fr]">
          <Reveal>
            <Badge tone="signal" pulse>Live — voice + chat support agent</Badge>
            <h1 className="font-display mt-6 text-[40px] font-light leading-[1.08] tracking-[-0.96px] text-ink md:text-[48px]">
              Customer support that picks up the phone.
            </h1>
            <div className="mt-8 flex flex-wrap items-center gap-3">
              <ButtonLink to="/demo" variant="primary" className="!px-6 !py-3 !text-[15px]">
                Try the live demo
              </ButtonLink>
              <ButtonLink to="/how-it-works" variant="ghost" className="!px-6 !py-3 !text-[15px]">
                How it works
              </ButtonLink>
            </div>
          </Reveal>
          <Reveal delay={120}>
            <p className="max-w-md text-[16px] leading-[1.5] tracking-[0.16px] text-smoke md:ml-auto">
              VoxTicket is a voice-first AI support agent for e-commerce. Callers talk naturally —
              in English, Urdu, or Roman Urdu — and the agent resolves orders, shipments, payments,
              returns, and refunds in real time, under deterministic business rules.
            </p>
            <div className="mt-8 flex flex-wrap gap-x-8 gap-y-2 font-mono text-[12px] text-smoke md:justify-end">
              <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-teal" />REALTIME VOICE</span>
              <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-brand" />EN / UR / ROMAN URDU</span>
              <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-ok" />DETERMINISTIC ACTIONS</span>
            </div>
          </Reveal>
        </div>

        {/* feature panel: sphere + live call */}
        <Reveal delay={200}>
          <div className="mt-16 grid items-center gap-10 rounded-2xl bg-surface p-8 md:mt-20 md:grid-cols-[auto_1fr] md:p-12">
            <div className="flex flex-col items-center gap-6 text-center md:items-start md:text-left">
              <AudioSphere />
              <p className="max-w-[240px] text-[14px] leading-relaxed text-smoke">
                One call, one agent — voice in, resolution out. The sphere is
                the product at rest; the panel beside it is the product at work.
              </p>
            </div>
            <ProductCard />
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------------- built-on strip ---------------- */

const STACK = ['LiveKit', 'AssemblyAI', 'Cartesia', 'Spring Boot', 'PostgreSQL', 'pgvector'];

function StackStrip() {
  return (
    <section className="border-y border-line">
      <div className="mx-auto max-w-[1280px] px-6 py-10">
        <Reveal>
          <div className="text-center font-mono text-[11px] text-smoke">
            Built on production infrastructure
          </div>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-x-12 gap-y-4">
            {STACK.map((s) => (
              <span key={s} className="text-[15px] font-medium text-smoke transition-colors hover:text-ink">
                {s}
              </span>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------------- capabilities ---------------- */

const CAPABILITIES = [
  {
    title: 'Realtime voice conversations',
    body: 'Sub-second turn-taking over LiveKit with AssemblyAI streaming transcription and Cartesia neural voices. Callers can interrupt — the agent stops, listens, and responds.',
  },
  {
    title: 'Order, shipment & payment awareness',
    body: 'The agent reads live order state — items, payments, shipments, tracking — and answers from the system of record, not from a script.',
  },
  {
    title: 'Cancellations, returns & refunds',
    body: 'Multi-step procedures with explicit confirmation and OTP verification. Every mutation is deterministic, audited, and reversible where policy allows.',
  },
  {
    title: 'Multilingual by default',
    body: 'English, Urdu, and Roman Urdu with automatic language detection and per-reply voice switching. No language menu — the agent follows the caller.',
  },
  {
    title: 'Guarded, not just prompted',
    body: 'AI understands the request; the Java backend controls what the system is allowed to do. Prompt-injection filtering, ownership checks, and confirmation gates are enforced in code.',
  },
  {
    title: 'Full observability',
    body: 'Every turn emits a decision trace — model, tools, latency, RAG hits, procedure outcomes. The operations console shows exactly what the agent did and why.',
  },
];

function Capabilities() {
  return (
    <section className="bg-surface">
      <div className="mx-auto max-w-[1280px] px-6 py-24">
        <Reveal>
          <Kicker>Capabilities</Kicker>
          <h2 className="font-display mt-4 max-w-2xl text-[32px] font-light leading-[1.13] tracking-[-0.64px] text-ink md:text-[36px] md:leading-[1.17] md:tracking-[-0.72px]">
            A support agent, not a chatbot.
          </h2>
        </Reveal>
        <div className="mt-12 grid gap-6 md:grid-cols-2 lg:grid-cols-3">
          {CAPABILITIES.map((c, i) => (
            <Reveal key={c.title} delay={(i % 3) * 80}>
              <div className="h-full rounded-xl border border-line bg-paper p-8 shadow-card">
                <h3 className="text-[20px] font-medium leading-[1.3] tracking-[-0.2px] text-ink">{c.title}</h3>
                <p className="mt-2 text-[15px] leading-[1.6] text-smoke">{c.body}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

/* ---------------- how it works ---------------- */

const STEPS = [
  { n: '01', title: 'Caller speaks', body: 'Audio streams over LiveKit. AssemblyAI transcribes in real time with Urdu-capable models; the turn detector knows when the caller finished.' },
  { n: '02', title: 'Agent reasons', body: 'The transcript goes to the conversation engine, which selects a model tier, retrieves policy knowledge, and calls tools — order lookups, shipment tracking, procedure controls.' },
  { n: '03', title: 'Agent responds', body: 'The reply streams back sentence-by-sentence to Cartesia TTS. The caller hears the first words while the rest is still generating — and can interrupt at any point.' },
  { n: '04', title: 'System acts — safely', body: 'Cancellations, returns, and refunds run as deterministic procedures: confirmation first, OTP verification where required, every step audited.' },
];

function HowItWorks() {
  return (
    <section>
      <div className="mx-auto max-w-[1280px] px-6 py-24">
        <Reveal>
          <Kicker>How it works</Kicker>
          <div className="mt-4 flex flex-wrap items-end justify-between gap-6">
            <h2 className="font-display max-w-xl text-[32px] font-light leading-[1.13] tracking-[-0.64px] text-ink md:text-[36px] md:leading-[1.17] md:tracking-[-0.72px]">
              Speech in, resolution out.
            </h2>
            <Link to="/how-it-works" className="group text-[14px] font-medium text-brand-ink underline decoration-brand/30 underline-offset-4 hover:decoration-brand">
              Full walkthrough <span className="inline-block transition-transform group-hover:translate-x-1">→</span>
            </Link>
          </div>
        </Reveal>
        <div className="mt-12 grid gap-8 sm:grid-cols-2 lg:grid-cols-4">
          {STEPS.map((s, i) => (
            <Reveal key={s.n} delay={i * 80}>
              <div className="border-t-2 border-ink pt-5">
                <div className="font-mono text-[12px] text-brand-ink">{s.n}</div>
                <h3 className="mt-2.5 text-[15px] font-medium text-ink">{s.title}</h3>
                <p className="mt-2 text-[14px] leading-relaxed text-smoke">{s.body}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

/* ---------------- principle ---------------- */

function Principle() {
  return (
    <section className="border-t border-line bg-surface">
      <div className="mx-auto max-w-[1280px] px-6 py-24">
        <div className="grid items-center gap-10 md:grid-cols-2">
          <Reveal>
            <Kicker>Design principle</Kicker>
            <p className="mt-4 max-w-xl font-display text-[28px] font-light leading-[1.23] tracking-[-0.02em] text-ink md:text-[32px] md:leading-[1.13]">
              “AI understands the request. Java controls what happens next.”
            </p>
          </Reveal>
          <Reveal delay={120}>
            <p className="max-w-md text-[17px] font-medium leading-[1.6] text-ink-dim">
              The language model is never trusted with business actions. It proposes; deterministic
              Java procedures dispose. Confirmations, OTP verification, ownership checks, and audit
              trails are enforced in code — so a clever prompt can never cancel someone else&apos;s order.
            </p>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

/* ---------------- CTA ---------------- */

function Cta() {
  return (
    <section>
      <div className="mx-auto max-w-[1280px] px-6 py-24">
        <Reveal>
          <div className="relative overflow-hidden rounded-2xl bg-surface p-8 md:p-14">
            <h2 className="font-display max-w-xl text-[32px] font-light leading-[1.13] tracking-[-0.64px] text-ink md:text-[36px] md:leading-[1.17] md:tracking-[-0.72px]">
              Talk to the agent yourself.
            </h2>
            <p className="mt-4 max-w-xl text-[16px] leading-relaxed text-smoke">
              Open a chat or voice session with a sample customer account and try tracking an order,
              starting a return, or switching to Urdu mid-conversation.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <ButtonLink to="/demo" variant="primary" className="!px-6 !py-3 !text-[15px]">
                Open the demo
              </ButtonLink>
              <ButtonLink to="/technology" variant="ghost" className="!px-6 !py-3 !text-[15px]">
                Read the architecture
              </ButtonLink>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

export function Landing() {
  return (
    <Page>
      <Hero />
      <StackStrip />
      <Capabilities />
      <HowItWorks />
      <Principle />
      <Cta />
    </Page>
  );
}
