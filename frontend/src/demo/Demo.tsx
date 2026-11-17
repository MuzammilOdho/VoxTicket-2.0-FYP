/** VoxTicket demo — ElevenLabs system (DESIGN-ELEVENLABS.md).
 *
 *  One page, one session: the service assigns a sample customer account
 *  on every load (a reload starts a new session with a new sample
 *  account). Chat and voice share a single composer — the mic button
 *  starts a LiveKit voice call inline. Confirmations and OTP verification
 *  are inline inside the chat message — never a blocking popup — so the
 *  user can keep chatting.
 */
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useDemoChat, type ChatMessage } from './useDemoChat';
import { useDemoCustomer, type DemoCustomer } from './useDemoCustomer';
import { useVoiceCall, formatElapsed, type Speaking } from './useVoiceCall';
import { useLiveCaptions } from './useLiveCaptions';
import { AdminPreview } from './AdminPreview';
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
  const [adminPreviewOpen, setAdminPreviewOpen] = useState(false);

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
      <div className="flex min-h-screen items-center justify-center bg-paper">
        <Loading label="Starting your demo session" />
      </div>
    );
  }

  if (error || !customer) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-paper px-6">
        <div className="w-full max-w-md">
          <ErrorBox
            message={error ?? 'Could not assign a customer'}
            onRetry={reassign}
          />
          <p className="mt-4 text-center text-[13px] text-slate-700">
            The service may be unavailable. Check that it is running and try again.
          </p>
          <div className="mt-3 text-center">
            <Link to="/" className="text-[13px] text-slate-600 transition-colors hover:text-ink">
              ← Back to site
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-paper text-ink lg:h-[100dvh]">
      <audio ref={voice.audioRef} autoPlay playsInline className="hidden" />

      <header className="sticky top-0 z-30 h-16 shrink-0 border-b border-line bg-paper">
        <div className="mx-auto flex h-16 max-w-[1280px] items-center justify-between gap-4 px-6">
          <Link to="/" className="flex shrink-0 items-center gap-2.5">
            <span className="flex h-6 w-6 items-center justify-center rounded-full bg-brand text-[11px] font-semibold text-white">
              V
            </span>
            <span className="font-display text-[16px] font-semibold tracking-[-0.01em] text-ink">VoxTicket demo</span>
          </Link>
          <div className="flex min-w-0 items-center gap-3">
            <Badge tone="signal" pulse>
              <span className="hidden sm:inline">Demo account ·&nbsp;</span>{customer.name}
            </Badge>
            <span className="hidden font-mono text-[12px] text-slate-700 md:block">{customer.phone}</span>
            {chat.sessionId && (
              <span className="hidden font-mono text-[11px] text-slate-700 lg:block">
                {chat.sessionId.slice(0, 18)}…
              </span>
            )}
            <button
              onClick={() => setAdminPreviewOpen(true)}
              title="Preview the operations console"
              className="shrink-0 rounded-full border border-brand/40 px-3 py-1.5 text-[13px] font-medium text-brand-ink transition-colors hover:border-brand hover:bg-brand-soft"
            >
              Preview admin console
            </button>
            <button
              onClick={newSession}
              title="Start a new session with a new customer"
              className="shrink-0 rounded-full border border-line px-3 py-1.5 text-[13px] text-slate-600 transition-colors hover:border-cloud-200 hover:text-ink"
            >
              New session
            </button>
          </div>
        </div>
      </header>

      <div className="mx-auto grid w-full max-w-[1280px] flex-1 min-h-0 lg:grid-cols-[1fr_340px] lg:overflow-hidden">
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
        <aside className="border-t border-line bg-surface p-6 lg:border-l lg:border-t-0 lg:h-full lg:min-h-0 lg:overflow-y-auto">
          <Kicker>Customer</Kicker>
          <div className="mt-3 text-[18px] font-medium tracking-[-0.01em] text-ink">{customer.name}</div>
          <div className="mt-1 font-mono text-[12px] text-slate-700">{customer.phone}</div>
          <div className="mt-0.5 truncate font-mono text-[12px] text-slate-700">{customer.email}</div>
          <div className="mt-2.5">
            <Badge tone={customer.status === 'ACTIVE' ? 'ok' : 'warn'}>{customer.status}</Badge>
          </div>

          <Kicker className="mt-7">Orders ({customer.orders.length})</Kicker>
          <div className="mt-3 space-y-2.5">
            {customer.orders.map((o) => (
              <button
                key={o.number}
                onClick={() => sendText(`Tell me about order ${o.number}`)}
                className="block w-full rounded-xl border border-line bg-paper p-4 text-left shadow-card transition-colors hover:border-cloud-200"
                title="Ask the agent about this order"
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-[12px] text-ink">{o.number}</span>
                  <Badge tone="mute">{o.status}</Badge>
                </div>
                <div className="mt-1.5 text-[13px] leading-snug text-ink">{o.items}</div>
                <div className="mt-1 font-mono text-[11px] text-slate-700">
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
                className="block w-full rounded-xl border border-line bg-paper px-3.5 py-2.5 text-left text-[13px] text-ink-dim transition-colors hover:border-cloud-200 hover:text-ink"
              >
                “{q}”
              </button>
            ))}
          </div>

          <div className="mt-8 border-t border-line/60 pt-5">
            <Kicker>Sample account</Kicker>
            <p className="mt-2.5 text-[12px] leading-relaxed text-slate-700">
              This is a sample customer account provided for the live demo. The
              orders, payments and shipments shown here are sample data. The
              agent reads them the same way it reads real customer records —
              and the same rules control what it is allowed to do.
            </p>
          </div>
        </aside>
      </div>
      {/* Spacer so the fixed mobile composer never covers bottom content. */}
      <div className="h-28 shrink-0 lg:hidden" aria-hidden />

      <AdminPreview
        open={adminPreviewOpen}
        onClose={() => setAdminPreviewOpen(false)}
        sessionId={chat.sessionId}
      />
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
          <div className="mx-auto flex min-h-[55vh] max-w-md flex-col items-center justify-center text-center">
            <Badge tone="signal" pulse>Session started</Badge>
            <div className="mt-4 text-[16px] font-medium text-ink">You're {customer.name}.</div>
            <p className="mt-2.5 text-[14px] leading-relaxed text-slate-600">
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
                  ? 'rounded-xl rounded-br-md bg-brand text-white'
                  : m.error
                    ? 'rounded-xl rounded-bl-md border border-bad/40 bg-bad/5 text-[#b42318]'
                    : 'rounded-xl rounded-bl-md border border-line bg-paper text-ink'
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
                    className="rounded-full bg-brand px-4 py-1.5 text-[13px] font-medium text-white transition-colors hover:bg-brand-strong disabled:opacity-40"
                  >
                    Confirm
                  </button>
                  <button
                    onClick={() => onSend('no')}
                    disabled={busy}
                    className="rounded-full border border-line bg-paper px-4 py-1.5 text-[13px] text-ink-dim transition-colors hover:border-cloud-200 hover:text-ink disabled:opacity-40"
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
            <span className="flex items-center gap-2.5 rounded-full border border-line bg-surface py-1.5 pl-3 pr-2 font-mono text-[11px] text-slate-700">
              Voice call ended · {formatElapsed(callNotice)}
              <button
                onClick={() => setCallNotice(null)}
                className="rounded-full p-1 transition-colors hover:bg-haze hover:text-ink"
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
              <button onClick={voice.dismissError} className="text-[13px] text-slate-700 transition-colors hover:text-ink">
                Dismiss
              </button>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Gemini-style unified composer — fixed to the viewport bottom on
          mobile; a normal flex footer inside the locked desktop layout. */}
      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-paper p-4 lg:static lg:shrink-0">
        <div className="flex items-center gap-2 rounded-md border border-line bg-paper py-2 pl-5 pr-2 shadow-sm transition-colors focus-within:border-brand focus-within:ring-2 focus-within:ring-brand/15">
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
            className="min-w-0 flex-1 bg-transparent text-[14px] text-ink outline-none placeholder:text-fog-400 disabled:opacity-50"
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
                ? 'bg-bad text-white hover:bg-[#b42318]'
                : 'text-slate-600 hover:bg-surface hover:text-ink'
            }`}
          >
            {voice.state === 'live' ? <EndCallIcon /> : <CallIcon />}
          </button>
          <button
            onClick={submit}
            disabled={busy || !draft.trim()}
            title="Send"
            className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-brand text-white transition-colors hover:bg-brand-strong disabled:cursor-not-allowed disabled:opacity-30"
          >
            <SendIcon />
          </button>
        </div>
        <div className="mt-2.5 flex items-center justify-between px-1">
          <span className="font-mono text-[11px] text-slate-700">
            {voice.state === 'live'
              ? `Voice call live · ${formatElapsed(voice.elapsedSec)}`
              : busy
                ? 'Agent is responding…'
                : 'Connected'}
          </span>
          {messages.length > 0 && (
            <button onClick={onReset} className="font-mono text-[11px] text-slate-700 transition-colors hover:text-ink">
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
    <div className="mt-3 rounded-xl border border-line bg-surface p-3">
      <div className="text-[11px] font-medium text-slate-600">
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
          className="min-w-0 flex-1 rounded-md border border-line bg-paper px-3 py-2 text-center font-mono text-[18px] tracking-[0.3em] text-ink outline-none transition-colors placeholder:text-fog-400 focus:border-brand disabled:opacity-50"
        />
        <button
          onClick={submit}
          disabled={busy || code.trim().length === 0}
          className="shrink-0 rounded-full bg-brand px-4 py-2 text-[13px] font-medium text-white transition-colors hover:bg-brand-strong disabled:opacity-40"
        >
          Verify
        </button>
      </div>
      <div className="mt-2 flex items-center justify-between">
        <button
          onClick={() => onSubmit('resend code')}
          disabled={busy}
          className="text-[12px] text-slate-600 transition-colors hover:text-ink disabled:opacity-40"
        >
          Resend code
        </button>
        <span className="font-mono text-[10px] text-slate-700">dev log: event=dev_otp_delivery</span>
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
    <div className="mx-auto w-full max-w-4xl overflow-hidden rounded-xl border border-line bg-paper shadow-card">
      {/* header */}
      <div className="flex items-center justify-between border-b border-line px-5 py-3">
        <div className="flex items-center gap-2.5">
          <span className="relative flex h-2.5 w-2.5">
            <span className="vq-pulse absolute inline-flex h-full w-full rounded-full bg-teal" />
            <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-teal" />
          </span>
          <span className="font-mono text-[12px] text-slate-600">voxticket — live call</span>
        </div>
        <div className="flex items-center gap-2">
          <Badge tone={agentPresent ? 'signal' : 'mute'} pulse={agentPresent}>
            {agentPresent ? 'Connected' : state === 'live' ? 'Waiting for agent' : 'Connecting'}
          </Badge>
          {state === 'live' && (
            <Badge tone={voice.networkBadge.tone} title={voice.networkBadge.hint}>
              {voice.networkBadge.label}
            </Badge>
          )}
          <span className="font-mono text-[12px] text-slate-700">{formatElapsed(elapsedSec)}</span>
          <button
            onClick={onCollapse}
            title="Minimize call"
            className="rounded-full p-1.5 text-slate-600 transition-colors hover:bg-surface hover:text-ink"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
          <button
            onClick={voice.hangup}
            title="End call"
            className="rounded-full p-1.5 text-slate-600 transition-colors hover:bg-bad/10 hover:text-bad"
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
          <span className="text-[13px] text-slate-600">
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
                  <span className="shrink-0 text-slate-700">{fmtT(e.at)}</span>
                  <span className="text-slate-600">{e.text}</span>
                </div>
              ))}
              {finals.map((c) => (
                <div key={c.id} className="flex justify-end">
                  <div className="max-w-[85%] rounded-xl rounded-br-md bg-surface px-4 py-2.5 text-[14px] leading-relaxed text-ink">
                    {c.text}
                  </div>
                </div>
              ))}
              {interim && (
                <div className="flex justify-end">
                  <div className="max-w-[85%] rounded-xl rounded-br-md border border-line bg-paper px-4 py-2.5 text-[14px] italic leading-relaxed text-slate-700">
                    {interim.text}…
                  </div>
                </div>
              )}
              {finals.length === 0 && (
                <p className="pt-2 text-[13px] leading-relaxed text-slate-700">
                  {supported
                    ? 'Your speech appears here as live captions while you talk.'
                    : 'Live captions need Chrome or Edge — call events still appear here.'}
                </p>
              )}
            </div>

            {/* session facts */}
            <div className="border-t border-line bg-surface p-5 md:border-l md:border-t-0">
              <div className="mb-4 flex flex-col items-center">
                <div className={`audio-sphere h-[72px] w-[72px] rounded-full ${speaking === 'agent' ? 'audio-sphere-live' : ''}`} aria-hidden />
                <div className="mt-2 font-mono text-[10px] text-slate-700">
                  {speaking === 'agent' ? 'agent speaking' : speaking === 'you' ? 'you are speaking' : 'voice ready'}
                </div>
              </div>
              <div className="font-mono text-[10px] text-slate-700">Session</div>
              <div className="mt-4 space-y-3 font-mono text-[12px]">
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-slate-700">customer</span>
                  <span className="truncate text-ink">{customer.name}</span>
                </div>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-slate-700">agent</span>
                  <span className={agentPresent ? 'text-ink' : 'text-slate-600'}>
                    {agentPresent ? 'connected' : 'waiting…'}
                  </span>
                </div>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-slate-700">speaking</span>
                  <span className={speaking === 'agent' ? 'text-ink' : speaking === 'you' ? 'text-ink-dim' : 'text-slate-600'}>
                    {speaking === 'agent' ? 'agent' : speaking === 'you' ? 'you' : '—'}
                  </span>
                </div>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-slate-700">mic</span>
                  <span className={muted ? 'text-warn' : 'text-ink'}>{muted ? 'muted' : 'live'}</span>
                </div>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-slate-700">elapsed</span>
                  <span className="text-ink">{formatElapsed(elapsedSec)}</span>
                </div>
              </div>
              {live && !agentPresent && (
                <p className="mt-4 border-t border-line pt-4 text-[12px] leading-relaxed text-slate-700">
                  The voice agent hasn't joined yet. Check that the voice
                  service is running and try again.
                </p>
              )}
            </div>
          </div>

          {/* bottom: waveform + controls */}
          <div className="flex flex-wrap items-center justify-between gap-4 border-t border-line px-5 py-4">
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
                      : 'border-line text-ink-dim hover:border-cloud-200 hover:text-ink'
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
    <div className="mx-auto flex w-full max-w-lg items-center gap-3 rounded-full border border-line bg-paper py-2 pl-4 pr-2 shadow-card">
      <span className="relative flex h-2 w-2 shrink-0">
        <span className="vq-pulse absolute inline-flex h-full w-full rounded-full bg-teal" />
        <span className="relative inline-flex h-2 w-2 rounded-full bg-teal" />
      </span>
      <button onClick={onExpand} className="min-w-0 flex-1 truncate text-left text-[13px] text-ink-dim transition-colors hover:text-ink">
        Voice call · {formatElapsed(voice.elapsedSec)} · <VoiceStatusText state={voice.state} agentPresent={voice.agentPresent} speaking={voice.speaking} />
      </button>
      <button
        onClick={onExpand}
        title="Expand call"
        className="shrink-0 rounded-full p-2 text-slate-600 transition-colors hover:bg-surface hover:text-ink"
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
    return <span className="text-slate-600">{state === 'mic' ? 'Requesting microphone…' : 'Connecting…'}</span>;
  }
  if (!agentPresent) {
    return (
      <span className="flex items-center gap-2 text-slate-600">
        <span className="h-1.5 w-1.5 rounded-full bg-cloud-200" /> Waiting for agent…
      </span>
    );
  }
  if (speaking === 'agent') {
    return (
      <span className="flex items-center gap-2 text-ink">
        <span className="vq-pulse h-1.5 w-1.5 rounded-full bg-teal" /> Agent speaking…
      </span>
    );
  }
  if (speaking === 'you') {
    return (
      <span className="flex items-center gap-2 text-ink-dim">
        <span className="vq-pulse h-1.5 w-1.5 rounded-full bg-teal" /> You're speaking…
      </span>
    );
  }
  return (
    <span className="flex items-center gap-2 text-slate-600">
      <span className="h-1.5 w-1.5 rounded-full bg-ok" /> Listening…
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
          className={`w-[3px] rounded-full ${animated ? 'vq-bar bg-brand' : 'bg-cloud-200'}`}
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
