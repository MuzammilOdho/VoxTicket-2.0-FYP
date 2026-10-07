/** VoxTicket landing — Linear "midnight precision instrument". */
import { Link } from 'react-router-dom';
import { Page } from './chrome';
import { Badge, ButtonLink, Kicker } from '../ui/primitives';
import { Reveal, useHeroEntrance, useAmbientFloat } from '../ui/motion';

/* ---------------- hero product card ----------------
   A reconstruction of the live voice console — the product UI is the
   only visual texture on the page. */

function Waveform() {
  const bars = [34, 58, 44, 72, 90, 64, 80, 52, 68, 88, 46, 60, 74, 40, 56, 70, 48, 62, 36, 54, 78, 42, 66, 50];
  return (
    <div className="flex h-10 items-center gap-[3px]" aria-hidden>
      {bars.map((h, i) => (
        <span
          key={i}
          className="vq-bar w-[3px] rounded-full bg-signal"
          style={{ height: `${h}%`, animationDelay: `${(i % 8) * 0.12}s` }}
        />
      ))}
    </div>
  );
}

function ProductCard() {
  return (
    <div className="g-border lift relative overflow-hidden rounded-xl">
      {/* window chrome */}
      <div className="flex items-center gap-2 border-b border-line/70 px-5 py-3.5">
        <span className="h-2.5 w-2.5 rounded-full bg-smoke" />
        <span className="h-2.5 w-2.5 rounded-full bg-smoke" />
        <span className="h-2.5 w-2.5 rounded-full bg-smoke" />
        <span className="ml-3 font-mono text-[11px] text-ash">voxticket — live call</span>
        <span className="ml-auto flex items-center gap-1.5 font-mono text-[11px] text-pulse-green">
          <span className="vq-pulse inline-block h-1.5 w-1.5 rounded-full bg-pulse-green" />
          CONNECTED
        </span>
      </div>

      <div className="grid md:grid-cols-[1.5fr_1fr]">
        {/* transcript */}
        <div className="space-y-4 p-5 md:p-6">
          <div className="flex justify-end">
            <div className="max-w-[85%] rounded-xl rounded-br-md bg-white/[0.07] px-4 py-2.5 text-[14px] leading-relaxed text-mist">
              Where is my order? It's been a week.
            </div>
          </div>
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-xl rounded-bl-md border border-line bg-obsidian px-4 py-2.5 text-[14px] leading-relaxed text-mist">
              Order <span className="font-mono text-[13px] text-signal">VT-88412</span> shipped yesterday —
              it's with the courier in Karachi, arriving Thursday.
            </div>
          </div>
          <div className="flex justify-end">
            <div className="max-w-[85%] rounded-xl rounded-br-md bg-white/[0.07] px-4 py-2.5 text-[14px] leading-relaxed text-mist">
              Actually, cancel it instead.
            </div>
          </div>
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-xl rounded-bl-md border border-signal/30 bg-signal/[0.06] px-4 py-2.5 text-[14px] leading-relaxed text-mist">
              I can cancel <span className="font-mono text-[13px]">VT-88412</span> with a full refund.
              Shall I go ahead?
              <div className="mt-2.5 flex gap-2">
                <span className="rounded-md bg-signal px-3 py-1 text-[12px] font-medium text-[#08090a]">Confirm</span>
                <span className="rounded-md border border-line px-3 py-1 text-[12px] text-ink-dim">Keep order</span>
              </div>
            </div>
          </div>
          <div className="border-t border-line/60 pt-4">
            <Waveform />
            <div className="mt-2 flex items-center justify-between font-mono text-[11px] text-ash">
              <span>listening…</span>
              <span>turn latency 840ms</span>
            </div>
          </div>
        </div>

        {/* decision trace */}
        <div className="border-t border-line/70 bg-obsidian/60 p-5 md:border-l md:border-t-0 md:p-6">
          <div className="font-mono text-[10px] uppercase tracking-[0.14em] text-ash">decision trace</div>
          <div className="mt-4 space-y-3 font-mono text-[12px]">
            {[
              ['intent', 'cancel_order', 'text-iris-violet'],
              ['order', 'VT-88412 · owned ✓', 'text-mist'],
              ['policy', 'cancel ≤ 24h · refundable', 'text-mist'],
              ['guard', 'confirmation required', 'text-warn'],
              ['model', 'tier-2 · 840ms', 'text-ash'],
            ].map(([k, v, c]) => (
              <div key={k} className="flex items-baseline justify-between gap-3">
                <span className="text-ash">{k}</span>
                <span className={c}>{v}</span>
              </div>
            ))}
          </div>
          <div className="mt-5 border-t border-line/60 pt-4">
            <div className="font-mono text-[10px] uppercase tracking-[0.14em] text-ash">audit</div>
            <div className="mt-2 font-mono text-[12px] text-pulse-green">● every step recorded</div>
          </div>
        </div>
      </div>
    </div>
  );
}

function Hero() {
  const rootRef = useHeroEntrance<HTMLElement>();
  const gridRef = useAmbientFloat<HTMLDivElement>(18, 9);

  return (
    <section ref={rootRef} className="relative overflow-hidden">
      <div ref={gridRef} className="bg-grid absolute inset-0" aria-hidden />
      <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-white/15 to-transparent" aria-hidden />

      <div className="relative mx-auto max-w-[1200px] px-6 pb-16 pt-20 md:pb-24 md:pt-28">
        <div className="hero-el">
          <Badge tone="signal" pulse>Live — voice + chat support agent</Badge>
        </div>
        <h1 className="hero-el display-tight mt-6 max-w-4xl text-[48px] font-medium leading-[1.0] text-white md:text-[72px]">
          Customer support that picks up the phone.
        </h1>
        <p className="hero-el mt-6 max-w-2xl text-[16px] leading-[1.6] text-fog">
          VoxTicket is a voice-first AI support agent for e-commerce. Callers talk naturally —
          in English, Urdu, or Roman Urdu — and the agent resolves orders, shipments, payments,
          returns, and refunds in real time, under deterministic business rules.
        </p>
        <div className="hero-el mt-8 flex flex-wrap items-center gap-3">
          <ButtonLink to="/demo" variant="primary" className="acid-glow !px-6 !py-3 !text-[14px]">
            Try the live demo
          </ButtonLink>
          <ButtonLink to="/how-it-works" variant="ghost" className="!px-6 !py-3 !text-[14px]">
            How it works
          </ButtonLink>
        </div>
        <div className="hero-el mt-10 flex flex-wrap gap-x-8 gap-y-2 font-mono text-[12px] text-ash">
          <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-signal" />REALTIME VOICE</span>
          <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-signal" />EN / UR / ROMAN URDU</span>
          <span className="flex items-center gap-2"><span className="h-1.5 w-1.5 rounded-full bg-pulse-green" />DETERMINISTIC ACTIONS</span>
        </div>

        <div className="hero-el relative mt-14 md:mt-16">
          <ProductCard />
          <div className="hero-floor absolute -inset-x-24 -bottom-24 top-1/3 -z-10" aria-hidden />
        </div>
      </div>
    </section>
  );
}

/* ---------------- built-on strip ---------------- */

const STACK = ['LiveKit', 'AssemblyAI', 'Cartesia', 'Spring Boot', 'PostgreSQL', 'pgvector'];

function StackStrip() {
  return (
    <section className="border-y border-line/70">
      <div className="mx-auto max-w-[1200px] px-6 py-10">
        <Reveal>
          <div className="text-center font-mono text-[11px] uppercase tracking-[0.18em] text-ash">
            Built on production infrastructure
          </div>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-x-12 gap-y-4">
            {STACK.map((s) => (
              <span key={s} className="text-[15px] font-medium text-fog transition-colors hover:text-white">
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
    <section>
      <div className="mx-auto max-w-[1200px] px-6 py-24">
        <Reveal>
          <Kicker>Capabilities</Kicker>
          <h2 className="heading-tight mt-3 max-w-2xl text-[32px] font-medium leading-[1.13] text-white md:text-[48px] md:leading-[1.0]">
            A support agent, not a chatbot.
          </h2>
        </Reveal>
        <div className="mt-12 grid gap-x-12 gap-y-10 md:grid-cols-2">
          {CAPABILITIES.map((c, i) => (
            <Reveal key={c.title} delay={(i % 2) * 80}>
              <div className="border-t border-graphite pt-5">
                <h3 className="text-[17px] font-medium tracking-[-0.01em] text-white">{c.title}</h3>
                <p className="mt-2 max-w-md text-[15px] leading-[1.6] text-fog">{c.body}</p>
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
  { n: '02', title: 'Agent reasons', body: 'The transcript goes to the Java brain, which selects a model tier, retrieves policy knowledge, and calls tools — order lookups, shipment tracking, procedure controls.' },
  { n: '03', title: 'Agent responds', body: 'The reply streams back sentence-by-sentence to Cartesia TTS. The caller hears the first words while the rest is still generating — and can interrupt at any point.' },
  { n: '04', title: 'System acts — safely', body: 'Cancellations, returns, and refunds run as deterministic procedures: confirmation first, OTP verification where required, every step audited.' },
];

function HowItWorks() {
  return (
    <section className="border-t border-line/70">
      <div className="mx-auto max-w-[1200px] px-6 py-24">
        <Reveal>
          <Kicker>How it works</Kicker>
          <div className="mt-3 flex flex-wrap items-end justify-between gap-6">
            <h2 className="heading-tight max-w-xl text-[32px] font-medium leading-[1.13] text-white md:text-[48px] md:leading-[1.0]">
              Speech in, resolution out.
            </h2>
            <Link to="/how-it-works" className="group text-[14px] text-mist transition-colors hover:text-white">
              Full walkthrough <span className="inline-block transition-transform group-hover:translate-x-1">→</span>
            </Link>
          </div>
        </Reveal>
        <div className="mt-12 grid gap-8 sm:grid-cols-2 lg:grid-cols-4">
          {STEPS.map((s, i) => (
            <Reveal key={s.n} delay={i * 80}>
              <div className="border-t-2 border-signal/70 pt-5">
                <div className="font-mono text-[12px] text-signal">{s.n}</div>
                <h3 className="mt-2.5 text-[15px] font-medium text-white">{s.title}</h3>
                <p className="mt-2 text-[14px] leading-relaxed text-fog">{s.body}</p>
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
    <section className="border-t border-line/70">
      <div className="mx-auto max-w-[1200px] px-6 py-24">
        <div className="grid items-center gap-10 md:grid-cols-2">
          <Reveal>
            <Kicker>Design principle</Kicker>
            <h2 className="heading-tight mt-3 text-[32px] font-medium leading-[1.13] text-white md:text-[48px] md:leading-[1.05]">
              AI understands the request.<br />
              <span className="text-fog">Java controls what happens next.</span>
            </h2>
          </Reveal>
          <Reveal delay={120}>
            <p className="max-w-md text-[17px] font-medium leading-[1.6] text-mist">
              The language model is never trusted with business actions. It proposes; deterministic
              Java procedures dispose. Confirmations, OTP verification, ownership checks, and audit
              trails are enforced in code — so a clever prompt can never cancel someone else's order.
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
    <section className="border-t border-line/70">
      <div className="mx-auto max-w-[1200px] px-6 py-24">
        <Reveal>
          <div className="g-border relative overflow-hidden rounded-xl p-8 md:p-14">
            <div className="bg-grid absolute inset-0 opacity-60" aria-hidden />
            <div className="relative">
              <h2 className="heading-tight max-w-xl text-[32px] font-medium leading-[1.13] text-white md:text-[48px] md:leading-[1.0]">
                Talk to the agent yourself.
              </h2>
              <p className="mt-4 max-w-xl text-[16px] leading-relaxed text-fog">
                Pick a synthetic customer, open a chat or voice session, and try tracking an order,
                starting a return, or switching to Urdu mid-conversation.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <ButtonLink to="/demo" variant="primary" className="!px-6 !py-3 !text-[14px]">
                  Open the demo
                </ButtonLink>
                <ButtonLink to="/technology" variant="ghost" className="!px-6 !py-3 !text-[14px]">
                  Read the architecture
                </ButtonLink>
              </div>
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
