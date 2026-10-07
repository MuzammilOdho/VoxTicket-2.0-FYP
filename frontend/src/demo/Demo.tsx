/** VoxTicket demo — unified Gemini-style window.
 *
 *  One page, one session: the backend assigns a random seeded customer on
 *  every load (a reload is a new session as a new customer). Chat and voice
 *  share a single composer — the mic button starts a LiveKit voice call
 *  inline. Confirmations and OTP verification are inline inside the chat
 *  message — never a blocking popup — so the user can keep chatting.
 */
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useDemoChat, type ChatMessage } from './useDemoChat';
import { useDemoCustomer, type DemoCustomer } from './useDemoCustomer';
import { useVoiceCall, formatElapsed, type Speaking } from './useVoiceCall';
import { useLiveCaptions } from './useLiveCaptions';
import { Badge, ErrorBox, Kicker, Loading } from '../ui/primitives';

const TRY_ASKING = [
  'Where is my order?',
  'Cancel my latest order',
  'I want to return an item',
  'Switch to Urdu',
];

export function Demo() {
  const { customer, loading, error, reassign } = useDemoCustomer();
  const chat = useDemoChat();
  const voice = useVoiceCall();
  const [voiceCollapsed, setVoiceCollapsed] = useState(false);
  const [sessionEpoch, setSessionEpoch] = useState(0);

  const phone = customer?.phone ?? '';

  const newSession = () => {
    if (voice.active) voice.hangup();
    chat.reset();
    setVoiceCollapsed(false);
    setSessionEpoch((e) => e + 1);
    reassign();
  };

  const sendText = (text: string) => chat.send(text, phone);

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-void">
        <Loading label="Assigning your customer" />
      </div>
    );
  }

  if (error || !customer) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-void px-6">
        <div className="w-full max-w-md">
          <ErrorBox
            message={error ?? 'Could not assign a customer'}
            onRetry={reassign}
          />
          <p className="mt-4 text-center text-[13px] text-ash">
            The demo needs the backend running with the{' '}
            <span className="font-mono">dev</span> profile.
          </p>
          <div className="mt-3 text-center">
            <Link to="/" className="text-[13px] text-fog transition-colors hover:text-white">
              ← Back to site
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-void text-white lg:h-[100dvh]">
      <audio ref={voice.audioRef} autoPlay playsInline className="hidden" />

      <header className="glass sticky top-0 z-30 h-16 shrink-0 border-b border-line/70">
        <div className="mx-auto flex h-16 max-w-[1400px] items-center justify-between gap-4 px-6">
          <Link to="/" className="flex shrink-0 items-center gap-2.5">
            <span className="flex h-6 w-6 items-center justify-center rounded-[6px] bg-signal font-mono text-[11px] font-bold text-[#08090a]">
              V
            </span>
            <span className="text-[16px] font-medium tracking-[-0.01em] text-white">VoxTicket demo</span>
          </Link>
          <div className="flex min-w-0 items-center gap-3">
            <Badge tone="signal" pulse>
              <span className="hidden sm:inline">Testing as&nbsp;</span>{customer.name}
            </Badge>
            <span className="hidden font-mono text-[12px] text-ash md:block">{customer.phone}</span>
            {chat.sessionId && (
              <span className="hidden font-mono text-[11px] text-ash lg:block">
                {chat.sessionId.slice(0, 18)}…
              </span>
            )}
            <button
              onClick={newSession}
              title="Start a new session with a new customer"
              className="shrink-0 rounded-md border border-line px-3 py-1.5 text-[13px] text-fog transition-colors hover:border-smoke hover:text-white"
            >
              New session
            </button>
          </div>
        </div>
      </header>

      <div className="mx-auto grid w-full max-w-[1400px] flex-1 min-h-0 lg:grid-cols-[1fr_340px] lg:overflow-hidden">
        {/* ------- unified thread ------- */}
        <div className="flex min-h-0 flex-col">
          <Thread
            key={sessionEpoch}
            customer={customer}
            messages={chat.messages}
            busy={chat.busy}
            onSend={sendText}
            onReset={chat.reset}
            voice={voice}
            voiceCollapsed={voiceCollapsed}
            setVoiceCollapsed={setVoiceCollapsed}
          />
        </div>

        {/* ------- customer sidebar (from backend) ------- */}
        <aside className="border-t border-line/70 p-6 lg:border-l lg:border-t-0 lg:h-full lg:min-h-0 lg:overflow-y-auto">
          <Kicker>Customer</Kicker>
          <div className="mt-3 text-[18px] font-medium tracking-[-0.01em] text-white">{customer.name}</div>
          <div className="mt-1 font-mono text-[12px] text-ash">{customer.phone}</div>
          <div className="mt-0.5 truncate font-mono text-[12px] text-ash">{customer.email}</div>
          <div className="mt-2.5">
            <Badge tone={customer.status === 'ACTIVE' ? 'ok' : 'warn'}>{customer.status}</Badge>
          </div>

          <Kicker className="mt-7">Orders ({customer.orders.length})</Kicker>
          <div className="mt-3 space-y-2.5">
            {customer.orders.map((o) => (
              <button
                key={o.number}
                onClick={() => sendText(`Tell me about order ${o.number}`)}
                className="g-border-soft lift block w-full rounded-xl p-4 text-left"
                title="Ask the agent about this order"
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-[12px] text-signal">{o.number}</span>
                  <Badge tone="mute">{o.status}</Badge>
                </div>
                <div className="mt-1.5 text-[13px] leading-snug text-white">{o.items}</div>
                <div className="mt-1 font-mono text-[11px] text-ash">
                  {o.total} {o.currency} · {o.fulfillmentStatus}
                </div>
              </button>
            ))}
          </div>

          <Kicker className="mt-7">Try asking</Kicker>
          <div className="mt-3 space-y-2">
            {TRY_ASKING.map((q) => (
              <button
                key={q}
                onClick={() => sendText(q)}
                className="block w-full rounded-lg border border-line/70 bg-white/[0.02] px-3.5 py-2.5 text-left text-[13px] text-mist transition-colors hover:border-smoke hover:text-white"
              >
                “{q}”
              </button>
            ))}
          </div>

          <div className="mt-8 border-t border-line/60 pt-5">
            <Kicker>Seeded data</Kicker>
            <p className="mt-2.5 text-[12px] leading-relaxed text-ash">
              This customer comes straight from the backend seed dataset — the same
              records the agent reads. AI understands your request; deterministic
              Java procedures control what the system is allowed to do.
            </p>
          </div>
        </aside>
      </div>
      {/* Spacer so the fixed mobile composer never covers bottom content. */}
      <div className="h-28 shrink-0 lg:hidden" aria-hidden />
    </div>
  );
}

/* ================= thread + composer ================= */

function Thread({ customer, messages, busy, onSend, onReset, voice, voiceCollapsed, setVoiceCollapsed }: {
  customer: DemoCustomer;
  messages: ChatMessage[];
  busy: boolean;
  onSend: (text: string) => void;
  onReset: () => void;
  voice: ReturnType<typeof useVoiceCall>;
  voiceCollapsed: boolean;
  setVoiceCollapsed: (v: boolean) => void;
}) {
  const [draft, setDraft] = useState('');
  const [callNotice, setCallNotice] = useState<number | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [messages.length, voice.state, voiceCollapsed]);

  // Remember a finished call as a quiet transcript notice.
  useEffect(() => {
    if (voice.state === 'idle' && voice.lastCallSec > 0) setCallNotice(voice.lastCallSec);
  }, [voice.state, voice.lastCallSec]);

  const submit = () => {
    if (!draft.trim() || busy) return;
    onSend(draft);
    setDraft('');
  };

  // Inline actions render only on the latest actionable assistant message.
  let lastAssistantIdx = -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role === 'assistant' && !m.pending && !m.error) {
      lastAssistantIdx = i;
      break;
    }
  }

  const micLabel =
    voice.state === 'live' ? 'End voice call'
    : voice.state === 'mic' || voice.state === 'connecting' ? 'Connecting…'
    : 'Start voice call';


  return (
    <div className="flex min-h-[60vh] flex-col lg:h-full lg:min-h-0">
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-6">
        {messages.length === 0 && !voice.active && callNotice === null && (
          <div className="mx-auto max-w-md pt-12 text-center">
            <Badge tone="signal" pulse>Session started</Badge>
            <div className="mt-4 text-[16px] font-medium text-white">You're {customer.name}.</div>
            <p className="mt-2.5 text-[14px] leading-relaxed text-fog">
              Type below or tap the mic to talk. Ask about orders, shipments,
              returns — or switch to Urdu mid-conversation.
            </p>
          </div>
        )}

        {messages.map((m, idx) => (
          <div key={m.id} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div
              className={`max-w-[80%] px-4 py-2.5 text-[14px] leading-relaxed ${
                m.role === 'user'
                  ? 'rounded-2xl rounded-br-md bg-white text-[#08090a]'
                  : m.error
                    ? 'rounded-2xl rounded-bl-md border border-bad/40 text-bad'
                    : 'rounded-2xl rounded-bl-md border border-line/80 bg-carbon text-mist'
              }`}
            >
              {m.pending ? (
                <span className="vq-typing flex gap-1"><span>●</span><span>●</span><span>●</span></span>
              ) : (
                <span className="whitespace-pre-wrap">{m.text}</span>
              )}

              {/* Inline confirmation — never a popup; the user can keep chatting. */}
              {idx === lastAssistantIdx && m.requiresConfirmation && (
                <div className="mt-3 flex flex-wrap gap-2">
                  <button
                    onClick={() => onSend('yes')}
                    disabled={busy}
                    className="rounded-md bg-signal px-4 py-1.5 text-[13px] font-medium text-[#08090a] transition-all hover:brightness-110 disabled:opacity-40"
                  >
                    Confirm
                  </button>
                  <button
                    onClick={() => onSend('no')}
                    disabled={busy}
                    className="rounded-md border border-line px-4 py-1.5 text-[13px] text-mist transition-colors hover:border-smoke hover:text-white disabled:opacity-40"
                  >
                    Decline
                  </button>
                </div>
              )}

              {/* Inline OTP entry — same principle: no blocking modal. */}
              {idx === lastAssistantIdx && m.requiresVerification && (
                <OtpInline busy={busy} onSubmit={onSend} />
              )}
            </div>
          </div>
        ))}

        {callNotice !== null && (
          <div className="flex justify-center">
            <span className="flex items-center gap-2.5 rounded-full border border-line/70 bg-white/[0.03] py-1.5 pl-3 pr-2 font-mono text-[11px] text-ash">
              Voice call ended · {formatElapsed(callNotice)}
              <button
                onClick={() => setCallNotice(null)}
                className="rounded-full p-1 transition-colors hover:bg-white/[0.08] hover:text-white"
                title="Dismiss"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </span>
          </div>
        )}

        {voice.active && (
          voiceCollapsed
            ? <VoiceSlimBar voice={voice} onExpand={() => setVoiceCollapsed(false)} />
            : <VoiceCallView voice={voice} customer={customer} onCollapse={() => setVoiceCollapsed(true)} />
        )}
        {voice.state === 'error' && (
          <div className="mx-auto w-full max-w-md">
            <ErrorBox message={voice.detail ?? 'Could not start the voice call'} onRetry={() => voice.start(customer.phone)} />
            <div className="mt-3 text-center">
              <button onClick={voice.dismissError} className="text-[13px] text-ash transition-colors hover:text-white">
                Dismiss
              </button>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Gemini-style unified composer — fixed to the viewport bottom on
          mobile; a normal flex footer inside the locked desktop layout. */}
      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-line/70 bg-void p-4 lg:static lg:shrink-0">
        <div className="flex items-center gap-2 rounded-[28px] border border-line bg-carbon py-2 pl-5 pr-2 transition-colors focus-within:border-smoke">
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                submit();
              }
            }}
            placeholder={`Message as ${customer.name}…`}
            disabled={busy}
            className="min-w-0 flex-1 bg-transparent text-[14px] text-white outline-none placeholder:text-ash disabled:opacity-50"
          />
          <button
            onClick={() => {
              if (voice.active) voice.hangup();
              else {
                setVoiceCollapsed(false);
                voice.start(customer.phone);
              }
            }}
            title={micLabel}
            disabled={voice.state === 'mic' || voice.state === 'connecting'}
            className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full transition-all disabled:opacity-50 ${
              voice.state === 'live'
                ? 'bg-bad text-white shadow-[0_0_20px_-4px_rgba(235,87,87,0.5)] hover:brightness-110'
                : 'text-fog hover:bg-white/[0.06] hover:text-white'
            }`}
          >
            {voice.state === 'live' ? <EndCallIcon /> : <CallIcon />}
          </button>
          <button
            onClick={submit}
            disabled={busy || !draft.trim()}
            title="Send"
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-signal text-[#08090a] transition-all hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-30"
          >
            <SendIcon />
          </button>
        </div>
        <div className="mt-2.5 flex items-center justify-between px-1">
          <span className="font-mono text-[11px] text-ash">
            {voice.state === 'live'
              ? `Voice call live · ${formatElapsed(voice.elapsedSec)}`
              : busy
                ? 'Agent is responding…'
                : 'Connected to the Java brain'}
          </span>
          {messages.length > 0 && (
            <button onClick={onReset} className="font-mono text-[11px] text-ash transition-colors hover:text-white">
              Reset thread
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/* ================= inline OTP ================= */

function OtpInline({ busy, onSubmit }: {
  busy: boolean;
  onSubmit: (text: string) => void;
}) {
  const [code, setCode] = useState('');
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const submit = () => {
    const c = code.trim();
    if (c.length > 0 && !busy) {
      onSubmit(c);
      setCode('');
    }
  };

  return (
    <div className="mt-3 rounded-lg border border-line/70 bg-void/70 p-3">
      <div className="text-[11px] font-medium uppercase tracking-[0.14em] text-fog">
        Verification code
      </div>
      <div className="mt-2 flex gap-2">
        <input
          ref={inputRef}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              submit();
            }
          }}
          inputMode="numeric"
          placeholder="••••••"
          disabled={busy}
          className="min-w-0 flex-1 rounded-md border border-line bg-white/[0.02] px-3 py-2 text-center font-mono text-[18px] tracking-[0.3em] text-white outline-none transition-colors placeholder:text-ash focus:border-signal disabled:opacity-50"
        />
        <button
          onClick={submit}
          disabled={busy || code.trim().length === 0}
          className="shrink-0 rounded-md bg-signal px-4 py-2 text-[13px] font-medium text-[#08090a] transition-all hover:brightness-110 disabled:opacity-40"
        >
          Verify
        </button>
      </div>
      <div className="mt-2 flex items-center justify-between">
        <button
          onClick={() => onSubmit('resend code')}
          disabled={busy}
          className="text-[12px] text-fog transition-colors hover:text-white disabled:opacity-40"
        >
          Resend code
        </button>
        <span className="font-mono text-[10px] text-ash">dev log: event=dev_otp_delivery</span>
      </div>
    </div>
  );
}

/* ================= voice call ================= */

/* ================= live call window =================
   Two-pane live view modeled on the landing hero card:
   transcript (real call events + live captions of the caller) on the
   left, real session facts on the right, waveform + controls below.
   The agent's own words and its decision trace stay server-side — the
   browser has no access to them — so the right panel shows session
   facts, never a fabricated trace. */

interface CallEvent {
  id: number;
  at: Date;
  text: string;
}

function VoiceCallView({ voice, customer, onCollapse }: {
  voice: ReturnType<typeof useVoiceCall>;
  customer: DemoCustomer;
  onCollapse: () => void;
}) {
  const { state, agentPresent, speaking, muted, elapsedSec } = voice;
  const live = state === 'live';
  const { captions, supported } = useLiveCaptions(live);
  const [events, setEvents] = useState<CallEvent[]>([]);
  const evId = useRef(0);
  const prevAgent = useRef(agentPresent);
  const prevMuted = useRef(muted);

  const pushEvent = (text: string) =>
    setEvents((e) => [...e, { id: evId.current++, at: new Date(), text }]);

  useEffect(() => {
    pushEvent('Call started');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    if (agentPresent && !prevAgent.current) pushEvent('Agent connected');
    if (!agentPresent && prevAgent.current) pushEvent('Agent disconnected');
    prevAgent.current = agentPresent;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentPresent]);
  useEffect(() => {
    if (muted !== prevMuted.current) pushEvent(muted ? 'You muted the microphone' : 'You unmuted the microphone');
    prevMuted.current = muted;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [muted]);

  const fmtT = (d: Date) =>
    d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

  const finals = captions.filter((c) => c.final);
  const interim = captions.find((c) => !c.final);

  return (
    <div className="g-border mx-auto w-full max-w-4xl overflow-hidden rounded-xl">
      {/* header */}
      <div className="flex items-center justify-between border-b border-line/70 px-5 py-3">
        <div className="flex items-center gap-2.5">
          <span className="relative flex h-2.5 w-2.5">
            <span className="vq-pulse absolute inline-flex h-full w-full rounded-full bg-signal" />
            <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-signal" />
          </span>
          <span className="font-mono text-[12px] text-fog">voxticket — live call</span>
        </div>
        <div className="flex items-center gap-2">
          <Badge tone={agentPresent ? 'signal' : 'mute'} pulse={agentPresent}>
            {agentPresent ? 'Connected' : state === 'live' ? 'Waiting for agent' : 'Connecting'}
          </Badge>
          <span className="font-mono text-[12px] text-ash">{formatElapsed(elapsedSec)}</span>
          <button
            onClick={onCollapse}
            title="Minimize call"
            className="rounded-md p-1.5 text-ash transition-colors hover:bg-white/[0.06] hover:text-white"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
          <button
            onClick={voice.hangup}
            title="End call"
            className="rounded-md p-1.5 text-ash transition-colors hover:bg-bad/10 hover:text-bad"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>
      </div>

      {(state === 'mic' || state === 'connecting') ? (
        <div className="flex items-center justify-center gap-3 px-5 py-10">
          <WaveformBars count={5} animated />
          <span className="text-[13px] text-fog">
            {state === 'mic' ? 'Requesting microphone…' : 'Connecting to LiveKit…'}
          </span>
        </div>
      ) : (
        <>
          <div className="grid md:grid-cols-[1fr_240px]">
            {/* transcript */}
            <div className="max-h-[300px] min-h-[220px] space-y-2.5 overflow-y-auto p-5">
              {events.map((e) => (
                <div key={e.id} className="flex gap-3 font-mono text-[12px]">
                  <span className="shrink-0 text-ash">{fmtT(e.at)}</span>
                  <span className="text-fog">{e.text}</span>
                </div>
              ))}
              {finals.map((c) => (
                <div key={c.id} className="flex justify-end">
                  <div className="max-w-[85%] rounded-xl rounded-br-md bg-white/[0.07] px-4 py-2.5 text-[14px] leading-relaxed text-mist">
                    {c.text}
                  </div>
                </div>
              ))}
              {interim && (
                <div className="flex justify-end">
                  <div className="max-w-[85%] rounded-xl rounded-br-md bg-white/[0.03] px-4 py-2.5 text-[14px] italic leading-relaxed text-ash">
                    {interim.text}…
                  </div>
                </div>
              )}
              {finals.length === 0 && (
                <p className="pt-2 text-[13px] leading-relaxed text-ash">
                  {supported
                    ? 'Your speech appears here as live captions while you talk.'
                    : 'Live captions need Chrome or Edge — call events still appear here.'}
                </p>
              )}
            </div>

            {/* session facts */}
            <div className="border-t border-line/70 bg-obsidian/60 p-5 md:border-l md:border-t-0">
              <div className="font-mono text-[10px] uppercase tracking-[0.14em] text-ash">Session</div>
              <div className="mt-4 space-y-3 font-mono text-[12px]">
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-ash">customer</span>
                  <span className="truncate text-mist">{customer.name}</span>
                </div>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-ash">agent</span>
                  <span className={agentPresent ? 'text-signal' : 'text-fog'}>
                    {agentPresent ? 'connected' : 'waiting…'}
                  </span>
                </div>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-ash">speaking</span>
                  <span className={speaking === 'agent' ? 'text-signal' : speaking === 'you' ? 'text-signal-teal' : 'text-fog'}>
                    {speaking === 'agent' ? 'agent' : speaking === 'you' ? 'you' : '—'}
                  </span>
                </div>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-ash">mic</span>
                  <span className={muted ? 'text-warn' : 'text-mist'}>{muted ? 'muted' : 'live'}</span>
                </div>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-ash">elapsed</span>
                  <span className="text-mist">{formatElapsed(elapsedSec)}</span>
                </div>
              </div>
              {live && !agentPresent && (
                <p className="mt-4 border-t border-line/60 pt-4 text-[12px] leading-relaxed text-ash">
                  The voice agent hasn't joined yet — start{' '}
                  <span className="font-mono">python-voice/agent.py</span>.
                </p>
              )}
            </div>
          </div>

          {/* bottom: waveform + controls */}
          <div className="flex flex-wrap items-center justify-between gap-4 border-t border-line/70 px-5 py-4">
            <div className="flex items-center gap-3">
              <WaveformBars count={9} animated={speaking !== null} />
              <VoiceStatus state={state} agentPresent={agentPresent} speaking={speaking} />
            </div>
            {live && (
              <div className="flex items-center gap-2.5">
                <button
                  onClick={voice.toggleMute}
                  className={`rounded-full border px-5 py-2 text-[13px] font-medium transition-colors ${
                    muted
                      ? 'border-warn/60 bg-warn/10 text-warn'
                      : 'border-line text-mist hover:border-smoke hover:text-white'
                  }`}
                >
                  {muted ? 'Unmute' : 'Mute'}
                </button>
                <button
                  onClick={voice.hangup}
                  className="rounded-full border border-bad/50 px-5 py-2 text-[13px] font-medium text-bad transition-colors hover:bg-bad/10"
                >
                  End call
                </button>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function VoiceSlimBar({ voice, onExpand }: {
  voice: ReturnType<typeof useVoiceCall>;
  onExpand: () => void;
}) {
  return (
    <div className="g-border-soft mx-auto flex w-full max-w-lg items-center gap-3 rounded-full py-2 pl-4 pr-2">
      <span className="relative flex h-2 w-2 shrink-0">
        <span className="vq-pulse absolute inline-flex h-full w-full rounded-full bg-signal" />
        <span className="relative inline-flex h-2 w-2 rounded-full bg-signal" />
      </span>
      <button onClick={onExpand} className="min-w-0 flex-1 truncate text-left text-[13px] text-mist transition-colors hover:text-white">
        Voice call · {formatElapsed(voice.elapsedSec)} · <VoiceStatusText state={voice.state} agentPresent={voice.agentPresent} speaking={voice.speaking} />
      </button>
      <button
        onClick={onExpand}
        title="Expand call"
        className="shrink-0 rounded-full p-2 text-ash transition-colors hover:bg-white/[0.06] hover:text-white"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <polyline points="18 15 12 9 6 15" />
        </svg>
      </button>
      <button
        onClick={voice.hangup}
        className="shrink-0 rounded-full border border-bad/50 px-4 py-1.5 text-[12px] font-medium text-bad transition-colors hover:bg-bad/10"
      >
        End
      </button>
    </div>
  );
}


function VoiceStatus({ state, agentPresent, speaking }: {
  state: string;
  agentPresent: boolean;
  speaking: Speaking;
}) {
  return <span className="text-[13px]"><VoiceStatusText state={state} agentPresent={agentPresent} speaking={speaking} /></span>;
}

function VoiceStatusText({ state, agentPresent, speaking }: {
  state: string;
  agentPresent: boolean;
  speaking: Speaking;
}) {
  if (state !== 'live') {
    return <span className="text-fog">{state === 'mic' ? 'Requesting microphone…' : 'Connecting…'}</span>;
  }
  if (!agentPresent) {
    return (
      <span className="flex items-center gap-2 text-fog">
        <span className="h-1.5 w-1.5 rounded-full bg-ash" /> Waiting for agent…
      </span>
    );
  }
  if (speaking === 'agent') {
    return (
      <span className="flex items-center gap-2 text-signal">
        <span className="vq-pulse h-1.5 w-1.5 rounded-full bg-signal" /> Agent speaking…
      </span>
    );
  }
  if (speaking === 'you') {
    return (
      <span className="flex items-center gap-2 text-signal-teal">
        <span className="vq-pulse h-1.5 w-1.5 rounded-full bg-signal-teal" /> You're speaking…
      </span>
    );
  }
  return (
    <span className="flex items-center gap-2 text-fog">
      <span className="h-1.5 w-1.5 rounded-full bg-pulse-green" /> Listening…
    </span>
  );
}

function WaveformBars({ count, animated }: { count: number; animated: boolean }) {
  const heights = [38, 62, 45, 78, 92, 58, 70, 48, 66, 40, 74, 52];
  return (
    <div className="flex h-9 items-center gap-[3px]" aria-hidden>
      {Array.from({ length: count }).map((_, i) => (
        <span
          key={i}
          className={`w-[3px] rounded-full ${animated ? 'vq-bar bg-signal' : 'bg-smoke'}`}
          style={{ height: `${heights[i % heights.length]}%`, animationDelay: `${(i % 6) * 0.12}s` }}
        />
      ))}
    </div>
  );
}

/* ================= composer icons ================= */

function CallIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
    </svg>
  );
}

function EndCallIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
      <line x1="4" y1="4" x2="20" y2="20" />
    </svg>
  );
}

function SendIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <line x1="12" y1="19" x2="12" y2="5" />
      <polyline points="5 12 12 5 19 12" />
    </svg>
  );
}
