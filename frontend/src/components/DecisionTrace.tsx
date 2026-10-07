import { useState } from 'react';
import type { TurnTrace, VoiceTurnMetric } from '../api/types';
import { Latency } from './Latency';
import { BoolBadge, StatusBadge } from './StatusBadge';
import { fmtInt } from './KpiCard';

function Node({
  title,
  status,
  headline,
  ms,
  children,
}: {
  title: string;
  status?: 'ok' | 'warn' | 'bad' | 'na';
  headline?: React.ReactNode;
  ms?: number | null;
  children?: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const dot =
    status === 'ok'
      ? 'bg-emerald-500'
      : status === 'warn'
        ? 'bg-amber-500'
        : status === 'bad'
          ? 'bg-rose-500'
          : 'bg-slate-300';
  return (
    <div className="min-w-[10.5rem] flex-1 rounded-none border border-line bg-raised shadow-none">
      <button onClick={() => setOpen((o) => !o)} className="flex w-full items-center gap-2 p-3 text-left">
        <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${dot}`} />
        <span className="min-w-0">
          <span className="block text-[11px] font-semibold uppercase tracking-wide text-ink-mute">{title}</span>
          <span className="block truncate text-sm font-medium text-ink">{headline ?? '—'}</span>
          {ms !== undefined && (
            <span className="block text-[11px] text-ink-mute">
              <Latency ms={ms} />
            </span>
          )}
        </span>
        <span className="ml-auto text-xs text-ink-mute">{open ? '▾' : '▸'}</span>
      </button>
      {open && children && <div className="border-t border-slate-100 p-3 text-xs text-ink-dim">{children}</div>}
    </div>
  );
}

function Arrow() {
  return <div className="hidden shrink-0 self-center text-slate-300 md:block">→</div>;
}

function Field({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-2 py-0.5">
      <span className="text-ink-mute">{k}</span>
      <span className="text-right font-medium text-ink-dim">{v}</span>
    </div>
  );
}

const na = <span className="text-ink-mute">n/a</span>;

export function DecisionTrace({ trace, voice }: { trace: TurnTrace; voice?: VoiceTurnMetric | null }) {
  const t = trace;
  const guardStatus = t.guardSuspicious === true ? 'bad' : t.guardSuspicious === false ? 'ok' : 'na';
  const outcomeStatus =
    t.aborted === true ? 'bad' : t.errorCode ? 'bad' : t.outcome && /fail|error|block/i.test(t.outcome) ? 'warn' : 'ok';
  const ragStatus = t.ragCacheHit === true ? 'ok' : t.ragDocs && t.ragDocs.length > 0 ? 'ok' : 'na';
  const procStatus = t.procedureOutcomeCode ? (/fail|error|denied/i.test(t.procedureOutcomeCode) ? 'warn' : 'ok') : 'na';

  return (
    <div>
      <div className="flex flex-col gap-2 md:flex-row md:flex-wrap md:items-stretch">
        <Node title="User input" status="ok" headline={`turn #${t.turnNumber} · ${t.channel}`}>
          <Field k="Language" v={t.language ?? na} />
          <Field k="Started" v={new Date(t.startedAt).toLocaleTimeString()} />
          <Field k="Trace" v={<span className="font-mono text-[10px]">{t.traceId.slice(0, 16)}…</span>} />
        </Node>
        <Arrow />
        <Node title="Guard" status={guardStatus} headline={t.guardSuspicious === true ? 'suspicious' : t.guardSuspicious === false ? 'pass' : 'n/a'} ms={t.guardMs}>
          <Field k="Category" v={t.guardCategory ?? na} />
          <Field k="Implementation" v={t.guardImplementation ?? na} />
          <Field k="Fallback" v={<BoolBadge value={t.guardFallback} />} />
        </Node>
        <Arrow />
        <Node title="Intent" status={t.intent ? 'ok' : 'na'} headline={t.intent ?? 'n/a'}>
          <Field k="Signals" v={t.intentSignals && t.intentSignals.length > 0 ? t.intentSignals.join(', ') : na} />
          <Field k="Language" v={t.language ?? na} />
        </Node>
        <Arrow />
        <Node title="Routing" status={t.tier ? 'ok' : 'na'} headline={t.tier ?? 'n/a'} ms={t.routingMs}>
          <Field k="Strategy" v={t.routingStrategy ?? na} />
          <Field k="Reason" v={t.routingReason ?? na} />
          <Field k="Margin" v={t.semanticMargin !== null && t.semanticMargin !== undefined ? t.semanticMargin.toFixed(4) : na} />
        </Node>
        <Arrow />
        <Node title="Tier / Model" status={t.model ? 'ok' : 'na'} headline={t.model ?? t.provider ?? 'n/a'}>
          <Field k="Tier" v={t.tier ?? na} />
          <Field k="Provider" v={t.provider ?? na} />
          <Field k="Prompt tokens" v={fmtInt(t.promptTokens)} />
          <Field k="Completion tokens" v={fmtInt(t.completionTokens)} />
        </Node>
        <Arrow />
        <Node title="RAG" status={ragStatus} headline={t.ragCacheHit === true ? 'cache hit' : t.ragDocs?.length ? `${t.ragDocs.length} docs` : 'n/a'} ms={t.ragMs}>
          <Field k="Cache hit" v={<BoolBadge value={t.ragCacheHit} />} />
          {t.ragDocs?.map((d, i) => (
            <Field key={i} k={d.docId} v={`${d.category ?? ''} · sim ${d.similarity?.toFixed(3) ?? 'n/a'}`} />
          ))}
        </Node>
        <Arrow />
        <Node title="Tools" status={t.tools && t.tools.length > 0 ? 'ok' : 'na'} headline={t.tools?.length ? `${t.tools.length} called` : 'none'} ms={t.toolMs}>
          {t.tools?.map((tool, i) => (
            <div key={i} className="flex items-center justify-between gap-2 py-0.5">
              <span className="font-mono text-[11px]">{tool.name}</span>
              <span className="flex items-center gap-2">
                <StatusBadge value={tool.result ?? 'unknown'} />
                <Latency ms={tool.durationMs} />
              </span>
            </div>
          ))}
        </Node>
        <Arrow />
        <Node title="Procedure" status={procStatus} headline={t.procedureType ?? 'none'}>
          <Field k="Status" v={t.procedureStatus ?? na} />
          <Field k="Outcome" v={t.procedureOutcomeCode ?? na} />
        </Node>
        <Arrow />
        <Node title="Response" status="ok" headline={t.llmTtftMs != null ? `TTFT ${t.llmTtftMs.toFixed(0)}ms` : 'n/a'} ms={t.llmTotalMs}>
          <Field k="TTFT" v={<Latency ms={t.llmTtftMs} />} />
          <Field k="Total LLM" v={<Latency ms={t.llmTotalMs} />} />
        </Node>
        <Arrow />
        <Node title="Outcome" status={outcomeStatus} headline={t.outcome ?? (t.aborted ? 'aborted' : 'n/a')}>
          <Field k="Aborted" v={<BoolBadge value={t.aborted} trueLabel="aborted" falseLabel="no" />} />
          <Field k="Error" v={t.errorCode ?? na} />
          <Field k="Ended" v={t.endedAt ? new Date(t.endedAt).toLocaleTimeString() : na} />
        </Node>
      </div>

      {voice && (
        <div className="mt-3 rounded-none border border-sky-200 bg-sky-50 p-3">
          <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-sky-700">
            Voice pipeline
            {voice.bargeIn && <StatusBadge value="BARGE_IN" />}
            {voice.aborted && <StatusBadge value="ABORTED" />}
            {voice.error && <StatusBadge value="ERROR" label={voice.error} />}
          </div>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
            <span className="flex items-center gap-2"><span className="text-sky-600 font-medium">STT</span><Latency ms={voice.sttLatencyMs} maxMs={voice.e2eMs ?? undefined} /></span>
            <span className="text-sky-300">→</span>
            <span className="flex items-center gap-2"><span className="text-sky-600 font-medium">Java Brain</span><Latency ms={voice.e2eMs} /></span>
            <span className="text-sky-300">→</span>
            <span className="flex items-center gap-2"><span className="text-sky-600 font-medium">TTFT</span><Latency ms={voice.brainTtftMs} maxMs={voice.e2eMs ?? undefined} /></span>
            <span className="text-sky-300">→</span>
            <span className="flex items-center gap-2"><span className="text-sky-600 font-medium">TTS First Audio</span><Latency ms={voice.ttsFirstAudioMs} maxMs={voice.e2eMs ?? undefined} /></span>
            <span className="text-sky-300">→</span>
            <span className="flex items-center gap-2"><span className="text-sky-600 font-medium">End-to-End</span><Latency ms={voice.e2eMs} /></span>
            {voice.sttLanguage && <span className="text-xs text-sky-600">STT lang: {voice.sttLanguage}</span>}
          </div>
        </div>
      )}
    </div>
  );
}
