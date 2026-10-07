/** VoxTicket demo: scenario picker -> customer preview -> support workspace. */
import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ANONYMOUS_CONTEXT, PERSONAS, SCENARIOS, type Persona } from './personas';
import { useDemoChat } from './useDemoChat';
import { Badge, Button, Loading, TextInput } from '../ui/primitives';

const VoiceSession = lazy(() => import('./VoiceSession').then((m) => ({ default: m.VoiceSession })));

type Mode = 'chat' | 'voice';
type Step = 'pick' | 'preview' | 'workspace';

export function Demo() {
  const [step, setStep] = useState<Step>('pick');
  const [persona, setPersona] = useState<Persona | null>(null);
  const [anonymous, setAnonymous] = useState(false);
  const [initialPrompt, setInitialPrompt] = useState<string | null>(null);

  if (step === 'pick' || (!persona && !anonymous)) {
    return (
      <ScenarioPicker
        onSelectPersona={(p, prompt) => {
          setPersona(p);
          setAnonymous(false);
          setInitialPrompt(prompt ?? null);
          setStep('preview');
        }}
        onSelectAnonymous={() => {
          setPersona(null);
          setAnonymous(true);
          setInitialPrompt(null);
          setStep('workspace');
        }}
      />
    );
  }

  if (step === 'preview' && persona) {
    return (
      <CustomerPreview
        persona={persona}
        initialPrompt={initialPrompt}
        onBack={() => setStep('pick')}
        onEnter={(prompt) => {
          setInitialPrompt(prompt);
          setStep('workspace');
        }}
      />
    );
  }

  return (
    <Workspace
      persona={persona}
      initialPrompt={initialPrompt}
      onChange={() => {
        setStep('pick');
        setPersona(null);
        setAnonymous(false);
        setInitialPrompt(null);
      }}
    />
  );
}

/* ---------------- scenario picker ---------------- */

function ScenarioPicker({ onSelectPersona, onSelectAnonymous }: {
  onSelectPersona: (p: Persona, prompt?: string) => void;
  onSelectAnonymous: () => void;
}) {
  const [filter, setFilter] = useState('');
  const scenarios = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return SCENARIOS;
    return SCENARIOS.filter((s) => s.title.toLowerCase().includes(q) || s.description.toLowerCase().includes(q));
  }, [filter]);

  const personaById = (id: string) => PERSONAS.find((p) => p.id === id)!;

  return (
    <div className="min-h-screen bg-canvas text-ink">
      <header className="border-b border-line">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-5">
          <Link to="/" className="flex items-center gap-2.5">
            <span className="flex h-6 w-6 items-center justify-center bg-signal font-mono text-xs font-bold text-[#101010]">V</span>
            <span className="text-[15px] font-semibold text-ink">VoxTicket demo</span>
          </Link>
          <span className="font-mono text-[11px] uppercase tracking-wider text-ink-mute">Synthetic environment</span>
        </div>
      </header>

      <div className="mx-auto max-w-6xl px-5 py-10">
        <h1 className="text-3xl font-semibold tracking-tight">What do you want to test?</h1>
        <p className="mt-2 max-w-2xl text-[15px] text-ink-dim">
          Pick a scenario — we'll assign you the right synthetic customer automatically,
          with a real phone number from the demo dataset. Or browse customers directly.
        </p>

        <div className="mt-6 max-w-sm">
          <TextInput placeholder="Filter scenarios…" value={filter} onChange={(e) => setFilter(e.target.value)} />
        </div>

        <div className="mt-6 grid gap-px border border-line bg-line sm:grid-cols-2 lg:grid-cols-3">
          {scenarios.map((s) => {
            const p = personaById(s.personaId);
            return (
              <button
                key={s.id}
                onClick={() => onSelectPersona(p, s.prompt)}
                className="group bg-raised p-5 text-left transition-colors hover:bg-raised-2"
              >
                <div className="text-[15px] font-semibold text-ink group-hover:text-signal">{s.title}</div>
                <p className="mt-2 text-sm leading-relaxed text-ink-dim">{s.description}</p>
                <div className="mt-3 font-mono text-[11px] text-ink-mute">as {p.name} · {p.phone}</div>
              </button>
            );
          })}
        </div>

        <div className="mt-10">
          <h2 className="text-xl font-semibold tracking-tight">Or pick a customer directly</h2>
          <div className="mt-4 grid gap-px border border-line bg-line sm:grid-cols-2 lg:grid-cols-4">
            {PERSONAS.map((p) => (
              <button key={p.id} onClick={() => onSelectPersona(p)} className="group bg-raised p-4 text-left transition-colors hover:bg-raised-2">
                <div className="text-sm font-semibold text-ink group-hover:text-signal">{p.name}</div>
                <div className="mt-1 font-mono text-[11px] text-ink-mute">{p.phone}</div>
                <div className="mt-2 flex flex-wrap gap-1">
                  {p.tags.slice(0, 2).map((t) => (
                    <span key={t} className="border border-line px-1 py-0.5 font-mono text-[10px] uppercase text-ink-mute">{t}</span>
                  ))}
                </div>
              </button>
            ))}
          </div>
        </div>

        <div className="mt-10 border border-dashed border-line p-5">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <div className="text-[15px] font-semibold text-ink">Anonymous demo</div>
              <p className="mt-1 max-w-xl text-sm text-ink-dim">{ANONYMOUS_CONTEXT.note}</p>
            </div>
            <Button variant="ghost" onClick={onSelectAnonymous}>Start anonymous</Button>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ---------------- customer preview ---------------- */

function CustomerPreview({ persona, initialPrompt, onBack, onEnter }: {
  persona: Persona;
  initialPrompt: string | null;
  onBack: () => void;
  onEnter: (prompt: string | null) => void;
}) {
  return (
    <div className="min-h-screen bg-canvas text-ink">
      <header className="border-b border-line">
        <div className="mx-auto flex h-14 max-w-4xl items-center justify-between px-5">
          <button onClick={onBack} className="text-sm text-ink-mute hover:text-ink">← Scenarios</button>
          <span className="font-mono text-[11px] uppercase tracking-wider text-ink-mute">Customer preview</span>
        </div>
      </header>

      <div className="mx-auto max-w-4xl px-5 py-10">
        <Badge tone="signal" pulse>Synthetic customer assigned</Badge>
        <h1 className="mt-4 text-3xl font-semibold tracking-tight">You are now testing as {persona.name}.</h1>
        <p className="mt-2 max-w-2xl text-[15px] text-ink-dim">{persona.scenario}</p>

        <div className="mt-8 grid gap-px border border-line bg-line md:grid-cols-2">
          <div className="bg-raised p-5">
            <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Identity</div>
            <dl className="mt-3 space-y-2 text-sm">
              <div className="flex justify-between"><dt className="text-ink-mute">Name</dt><dd className="text-ink">{persona.name}</dd></div>
              <div className="flex justify-between"><dt className="text-ink-mute">Phone</dt><dd className="font-mono text-ink">{persona.phone}</dd></div>
              <div className="flex justify-between"><dt className="text-ink-mute">Email</dt><dd className="font-mono text-xs text-ink">{persona.email}</dd></div>
              <div className="flex justify-between"><dt className="text-ink-mute">Status</dt><dd><Badge tone="ok">{persona.accountStatus}</Badge></dd></div>
            </dl>
          </div>
          <div className="bg-raised p-5">
            <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Orders ({persona.orders.length})</div>
            <div className="mt-3 space-y-3">
              {persona.orders.map((o) => (
                <div key={o.number} className="border-b border-line-soft pb-3 last:border-0 last:pb-0">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs text-signal">{o.number}</span>
                    <span className="text-xs text-ink-mute">{o.status}</span>
                  </div>
                  <div className="mt-1 text-sm text-ink">{o.items} · {o.total}</div>
                  <div className="mt-0.5 font-mono text-[11px] text-ink-mute">{o.payment}{o.note ? ` · ${o.note}` : ''}</div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {initialPrompt && (
          <div className="mt-6 border border-signal/40 bg-signal/5 p-4">
            <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-signal">Suggested first message</div>
            <div className="mt-2 text-sm text-ink">“{initialPrompt}”</div>
          </div>
        )}

        <div className="mt-8 flex gap-3">
          <Button variant="primary" onClick={() => onEnter(initialPrompt)} className="!px-8 !py-3">Enter demo workspace</Button>
          <Button variant="ghost" onClick={onBack} className="!px-6 !py-3">Choose different</Button>
        </div>
      </div>
    </div>
  );
}

/* ---------------- workspace ---------------- */

function Workspace({ persona, initialPrompt, onChange }: {
  persona: Persona | null;
  initialPrompt: string | null;
  onChange: () => void;
}) {
  const [mode, setMode] = useState<Mode>('chat');
  const chat = useDemoChat();
  const sentInitial = useRef(false);

  const phone = persona?.phone ?? '';
  const displayName = persona?.name ?? 'Anonymous';

  // Auto-send the scenario prompt on first entering chat mode.
  useEffect(() => {
    if (initialPrompt && mode === 'chat' && !sentInitial.current && chat.messages.length === 0) {
      sentInitial.current = true;
      chat.send(initialPrompt, phone);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  return (
    <div className="flex min-h-screen flex-col bg-canvas text-ink">
      <header className="border-b border-line">
        <div className="mx-auto flex h-14 max-w-[1400px] items-center justify-between px-5">
          <div className="flex items-center gap-4">
            <Link to="/" className="flex items-center gap-2.5">
              <span className="flex h-6 w-6 items-center justify-center bg-signal font-mono text-xs font-bold text-[#101010]">V</span>
              <span className="text-[15px] font-semibold text-ink">VoxTicket demo</span>
            </Link>
            <button onClick={onChange} className="text-sm text-ink-mute hover:text-ink">← Scenarios</button>
          </div>
          <div className="flex items-center gap-2">
            <Badge tone="signal" pulse>Synthetic</Badge>
            <span className="hidden font-mono text-xs text-ink-mute sm:block">{displayName}{phone ? ` · ${phone}` : ''}</span>
          </div>
        </div>
      </header>

      <div className="mx-auto grid w-full max-w-[1400px] flex-1 gap-px bg-line lg:grid-cols-[1fr_340px]">
        <div className="flex min-h-[70vh] flex-col bg-canvas">
          <div className="flex border-b border-line">
            {(['chat', 'voice'] as Mode[]).map((m) => (
              <button
                key={m}
                onClick={() => setMode(m)}
                className={`px-5 py-3 text-sm font-medium transition-colors ${
                  mode === m ? 'border-b-2 border-signal text-ink' : 'text-ink-mute hover:text-ink'
                }`}
              >
                {m === 'chat' ? 'Chat' : 'Voice'}
              </button>
            ))}
            {chat.sessionId && (
              <span className="ml-auto hidden items-center px-4 font-mono text-[11px] text-ink-mute sm:flex">
                {chat.sessionId.slice(0, 18)}…
              </span>
            )}
          </div>
          <div className="flex-1">
            {mode === 'chat' ? (
              <ChatPane displayName={displayName} phone={phone} chat={chat} />
            ) : (
              <Suspense fallback={<Loading label="Loading voice session" />}>
                <VoiceSession personaName={displayName} />
              </Suspense>
            )}
          </div>
        </div>

        <aside className="bg-raised p-5">
          {persona ? (
            <>
              <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Customer context</div>
              <div className="mt-3 text-lg font-semibold text-ink">{persona.name}</div>
              <div className="mt-0.5 font-mono text-xs text-ink-mute">{persona.phone}</div>
              <div className="mt-0.5 font-mono text-xs text-ink-mute">{persona.email}</div>
              <div className="mt-2"><Badge tone="ok">{persona.accountStatus}</Badge></div>

              <div className="mt-5 text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">
                Orders ({persona.orders.length})
              </div>
              <div className="mt-2 space-y-3">
                {persona.orders.map((o) => (
                  <button
                    key={o.number}
                    onClick={() => {
                      setMode('chat');
                      chat.send(`Tell me about order ${o.number}`, phone);
                    }}
                    className="block w-full border border-line bg-canvas p-3 text-left transition-colors hover:border-ink-mute"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-xs text-signal">{o.number}</span>
                      <span className="text-[11px] text-ink-mute">{o.status}</span>
                    </div>
                    <div className="mt-1 text-[13px] text-ink">{o.items}</div>
                    <div className="font-mono text-[11px] text-ink-mute">{o.total} · {o.payment}</div>
                  </button>
                ))}
              </div>

              <div className="mt-5 text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Try asking</div>
              <div className="mt-2 space-y-2">
                {persona.tryAsk.map((q) => (
                  <button
                    key={q}
                    onClick={() => {
                      setMode('chat');
                      chat.send(q, phone);
                    }}
                    className="block w-full border border-line bg-canvas px-3 py-2 text-left text-sm text-ink-dim transition-colors hover:border-ink-mute hover:text-ink"
                  >
                    “{q}”
                  </button>
                ))}
              </div>
            </>
          ) : (
            <>
              <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Anonymous session</div>
              <p className="mt-3 text-sm leading-relaxed text-ink-dim">{ANONYMOUS_CONTEXT.note}</p>
              <div className="mt-4 border border-dashed border-line p-3">
                <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Sample reference</div>
                <div className="mt-1 font-mono text-xs text-signal">{ANONYMOUS_CONTEXT.sampleOrder}</div>
                <p className="mt-1 text-xs text-ink-mute">Mention this order number to see how the agent handles unauthenticated lookups.</p>
              </div>
              <div className="mt-5 text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Try asking</div>
              <div className="mt-2 space-y-2">
                {ANONYMOUS_CONTEXT.tryAsk.map((q) => (
                  <button
                    key={q}
                    onClick={() => {
                      setMode('chat');
                      chat.send(q, '');
                    }}
                    className="block w-full border border-line bg-canvas px-3 py-2 text-left text-sm text-ink-dim transition-colors hover:border-ink-mute hover:text-ink"
                  >
                    “{q}”
                  </button>
                ))}
              </div>
            </>
          )}

          <div className="mt-6 border-t border-line-soft pt-4">
            <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">How this works</div>
            <p className="mt-2 text-xs leading-relaxed text-ink-mute">
              AI understands your request; deterministic Java procedures control what the system is allowed to do.
              Confirmations and OTP verification are enforced in code.
            </p>
          </div>
        </aside>
      </div>
    </div>
  );
}

/* ---------------- chat pane ---------------- */

function ChatPane({ displayName, phone, chat }: {
  displayName: string;
  phone: string;
  chat: ReturnType<typeof useDemoChat>;
}) {
  const [draft, setDraft] = useState('');
  const bottomRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [chat.messages.length]);

  const submit = () => {
    chat.send(draft, phone);
    setDraft('');
  };

  return (
    <div className="flex h-full min-h-[60vh] flex-col">
      <div className="flex-1 space-y-4 overflow-y-auto p-5">
        {chat.messages.length === 0 && (
          <div className="mx-auto max-w-md pt-10 text-center">
            <div className="text-[15px] font-medium text-ink">You're {displayName}.</div>
            <p className="mt-2 text-sm leading-relaxed text-ink-mute">
              Say hello to start. Ask about orders, shipments, returns — or switch to Urdu mid-conversation.
            </p>
          </div>
        )}
        {chat.messages.map((m) => (
          <div key={m.id} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div
              className={`max-w-[80%] px-4 py-2.5 text-sm leading-relaxed ${
                m.role === 'user'
                  ? 'bg-ink text-[#101010]'
                  : m.error
                    ? 'border border-bad/40 text-bad'
                    : 'border border-line bg-raised text-ink'
              }`}
            >
              {m.pending ? (
                <span className="vq-typing flex gap-1"><span>●</span><span>●</span><span>●</span></span>
              ) : (
                <span className="whitespace-pre-wrap">{m.text}</span>
              )}
              {(m.requiresConfirmation || m.requiresVerification) && !m.pending && (
                <div className="mt-2 flex gap-2">
                  <Badge tone="warn">{m.requiresConfirmation ? 'Awaiting confirmation' : 'Awaiting verification'}</Badge>
                </div>
              )}
            </div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      <div className="border-t border-line p-4">
        <div className="flex gap-2">
          <TextInput
            placeholder={`Message as ${displayName}…`}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                submit();
              }
            }}
            disabled={chat.busy}
            className="!py-2.5"
          />
          <Button variant="primary" onClick={submit} disabled={chat.busy || !draft.trim()}>Send</Button>
        </div>
        <div className="mt-2 flex items-center justify-between">
          <span className="font-mono text-[11px] text-ink-mute">
            {chat.busy ? 'Agent is responding…' : 'Connected to the Java brain'}
          </span>
          {chat.messages.length > 0 && (
            <button onClick={chat.reset} className="font-mono text-[11px] text-ink-mute hover:text-ink">Reset session</button>
          )}
        </div>
      </div>
    </div>
  );
}
