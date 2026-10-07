/** VoxTicket landing page. */
import { Link } from 'react-router-dom';
import { Page } from './chrome';
import { Badge, ButtonLink } from '../ui/primitives';

function Hero() {
  return (
    <section className="border-b border-line">
      <div className="mx-auto max-w-6xl px-5 pb-20 pt-20 md:pb-28 md:pt-28">
        <Badge tone="signal" pulse>Live — voice + chat support agent</Badge>
        <h1 className="mt-6 max-w-3xl text-4xl font-semibold leading-[1.08] tracking-tight text-ink md:text-6xl">
          Customer support that picks up the phone.
        </h1>
        <p className="mt-6 max-w-2xl text-lg leading-relaxed text-ink-dim">
          VoxTicket is a voice-first AI support agent for e-commerce. Callers talk naturally —
          in English, Urdu, or Roman Urdu — and the agent resolves orders, shipments, payments,
          returns, and refunds in real time, under deterministic business rules.
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          <ButtonLink to="/demo" variant="primary" className="!px-6 !py-3 !text-base">Try the live demo</ButtonLink>
          <ButtonLink to="/how-it-works" variant="ghost" className="!px-6 !py-3 !text-base">How it works</ButtonLink>
        </div>
        <div className="mt-10 flex flex-wrap gap-x-8 gap-y-2 font-mono text-xs text-ink-mute">
          <span><span className="text-signal">●</span> REALTIME VOICE</span>
          <span><span className="text-signal">●</span> EN / UR / ROMAN URDU</span>
          <span><span className="text-ok">●</span> DETERMINISTIC ACTIONS</span>
        </div>
      </div>
    </section>
  );
}

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
    <section className="border-b border-line">
      <div className="mx-auto max-w-6xl px-5 py-16 md:py-24">
        <div className="mb-10 max-w-2xl">
          <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-signal">Capabilities</div>
          <h2 className="mt-3 text-3xl font-semibold tracking-tight text-ink">A support agent, not a chatbot.</h2>
        </div>
        <div className="grid gap-px border border-line bg-line md:grid-cols-3">
          {CAPABILITIES.map((c) => (
            <div key={c.title} className="bg-raised p-6">
              <h3 className="text-[15px] font-semibold text-ink">{c.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-ink-dim">{c.body}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

const STEPS = [
  { n: '01', title: 'Caller speaks', body: 'Audio streams over LiveKit. AssemblyAI transcribes in real time with Urdu-capable models; the turn detector knows when the caller finished.' },
  { n: '02', title: 'Agent reasons', body: 'The transcript goes to the Java brain, which selects a model tier, retrieves policy knowledge, and calls tools — order lookups, shipment tracking, procedure controls.' },
  { n: '03', title: 'Agent responds', body: 'The reply streams back sentence-by-sentence to Cartesia TTS. The caller hears the first words while the rest is still generating — and can interrupt at any point.' },
  { n: '04', title: 'System acts — safely', body: 'Cancellations, returns, and refunds run as deterministic procedures: confirmation first, OTP verification where required, every step audited.' },
];

function HowItWorks() {
  return (
    <section className="border-b border-line">
      <div className="mx-auto max-w-6xl px-5 py-16 md:py-24">
        <div className="mb-10 max-w-2xl">
          <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-signal">How it works</div>
          <h2 className="mt-3 text-3xl font-semibold tracking-tight text-ink">Speech in, resolution out.</h2>
        </div>
        <div className="grid gap-6 md:grid-cols-4">
          {STEPS.map((s) => (
            <div key={s.n} className="border-t-2 border-signal/70 pt-4">
              <div className="font-mono text-xs text-signal">{s.n}</div>
              <h3 className="mt-2 text-[15px] font-semibold text-ink">{s.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-ink-dim">{s.body}</p>
            </div>
          ))}
        </div>
        <div className="mt-10">
          <Link to="/how-it-works" className="text-sm font-medium text-signal hover:underline">Full walkthrough →</Link>
        </div>
      </div>
    </section>
  );
}

function Principle() {
  return (
    <section className="border-b border-line bg-raised">
      <div className="mx-auto max-w-6xl px-5 py-16 md:py-20">
        <div className="grid gap-8 md:grid-cols-2 md:items-center">
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-signal">Design principle</div>
            <h2 className="mt-3 text-3xl font-semibold tracking-tight text-ink">AI understands the request.<br />Java controls what happens next.</h2>
          </div>
          <p className="text-[15px] leading-relaxed text-ink-dim">
            The language model is never trusted with business actions. It proposes; deterministic
            Java procedures dispose. Confirmations, OTP verification, ownership checks, and audit
            trails are enforced in code — so a clever prompt can never cancel someone else's order.
          </p>
        </div>
      </div>
    </section>
  );
}

function Cta() {
  return (
    <section>
      <div className="mx-auto max-w-6xl px-5 py-16 md:py-24">
        <div className="border border-line bg-raised p-8 md:p-12">
          <h2 className="max-w-xl text-3xl font-semibold tracking-tight text-ink">Talk to the agent yourself.</h2>
          <p className="mt-3 max-w-xl text-[15px] text-ink-dim">
            Pick a synthetic customer, open a chat or voice session, and try tracking an order,
            starting a return, or switching to Urdu mid-conversation.
          </p>
          <div className="mt-6 flex flex-wrap gap-3">
            <ButtonLink to="/demo" variant="primary" className="!px-6 !py-3">Open the demo</ButtonLink>
            <ButtonLink to="/technology" variant="ghost" className="!px-6 !py-3">Read the architecture</ButtonLink>
          </div>
        </div>
      </div>
    </section>
  );
}

export function Landing() {
  return (
    <Page>
      <Hero />
      <Capabilities />
      <HowItWorks />
      <Principle />
      <Cta />
    </Page>
  );
}
