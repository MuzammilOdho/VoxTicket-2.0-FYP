/** Shared shapes for the VoxTicket admin backend (GET /api/v1/admin/**). */

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type Channel = 'CHAT' | 'VOICE' | string;
export type SessionStatus = 'ACTIVE' | 'COMPLETED' | 'ABORTED' | 'EXPIRED' | string;
export type TurnOutcome = string;

/* ---------------- summary/v2 ---------------- */

export interface SummaryV2 {
  totals: { total: number; active: number; completed: number; aborted: number };
  byChannel: { chat: number; voice: number };
  rates: { resolutionRate: number | null; escalationRate: number | null };
  latency: { turnP50Ms: number | null; turnP95Ms: number | null };
  models: {
    byTier: Record<string, number>;
    byProvider: Record<string, number>;
    byModel: Record<string, number>;
  };
  tokens: { total: number; byProvider: Record<string, number> };
  cost: { estimatedUsd: number | null };
  tools: { name: string; calls: number; successRate: number | null; meanMs: number | null }[];
  rag: { searches: number; cacheHitRate: number | null; meanMs: number | null };
  procedures: { success: number; failure: number; clarification: number };
  recentErrors: {
    traceId: string;
    sessionId: string;
    turnNumber: number;
    outcome: string;
    errorCode: string | null;
    at: string;
  }[];
}

/* ---------------- conversations ---------------- */

export interface ConversationSummary {
  sessionId: string;
  channel: Channel;
  status: SessionStatus;
  lastTurnOutcome: TurnOutcome | null;
  turnCount: number;
  escalated: boolean;
  startedAt: string;
  lastActivityAt: string;
}

export interface ConversationSessionInfo {
  sessionId: string;
  channel: Channel;
  status: SessionStatus;
  customerId: string | null;
  identityAssurance: string | null;
  escalated: boolean;
  turnCount: number;
  startedAt: string;
  lastActivityAt: string;
}

export type TimelineKind = 'MESSAGE' | 'EVENT' | 'TRACE';

export interface TimelineEntry {
  kind: TimelineKind;
  turnNumber: number;
  at: string;
  label: string;
  detail?: string | null;
  traceId?: string | null;
}

export interface ConversationDetail {
  session: ConversationSessionInfo;
  timeline: TimelineEntry[];
}

/* ---------------- turn trace ---------------- */

export interface RagDocRef {
  docId: string;
  category: string | null;
  similarity: number | null;
}

export interface ToolTrace {
  name: string;
  result: string | null;
  durationMs: number | null;
}

export interface TurnTrace {
  sessionId: string;
  turnNumber: number;
  channel: Channel;
  traceId: string;
  parentTraceId: string | null;
  startedAt: string;
  endedAt: string | null;
  outcome: string | null;
  aborted: boolean | null;
  normalizeMs: number | null;
  guardMs: number | null;
  routingMs: number | null;
  llmTtftMs: number | null;
  llmTotalMs: number | null;
  ragMs: number | null;
  toolMs: number | null;
  guardSuspicious: boolean | null;
  guardCategory: string | null;
  guardImplementation: string | null;
  guardFallback: boolean | null;
  language: string | null;
  intent: string | null;
  intentSignals: string[] | null;
  routingStrategy: string | null;
  tier: string | null;
  routingReason: string | null;
  semanticMargin: number | null;
  provider: string | null;
  model: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  ragCacheHit: boolean | null;
  ragDocs: RagDocRef[] | null;
  tools: ToolTrace[] | null;
  procedureType: string | null;
  procedureStatus: string | null;
  procedureOutcomeCode: string | null;
  errorCode: string | null;
  decision: Record<string, unknown> | null;
}

/* ---------------- operations (generic flat rows) ---------------- */

export type OperationRow = Record<string, unknown>;

/* ---------------- voice ---------------- */

export interface VoiceCall {
  room: string;
  sessionId: string;
  outcome: string;
  bargeInCount: number;
  turnCount: number;
  startedAt: string;
  endedAt: string | null;
  sttProvider: string | null;
  ttsProvider: string | null;
}

export interface VoiceTurnMetric {
  turnNumber: number;
  traceId: string | null;
  sttLatencyMs: number | null;
  brainTtftMs: number | null;
  ttsFirstAudioMs: number | null;
  e2eMs: number | null;
  aborted: boolean | null;
  bargeIn: boolean | null;
  sttLanguage: string | null;
  error: string | null;
}

export interface VoiceCallDetail {
  call: VoiceCall;
  turns: VoiceTurnMetric[];
}

/* ---------------- audit ---------------- */

export interface AuditEvent {
  at: string;
  sessionId: string;
  turnNumber: number | null;
  type: string;
  detail: string | null;
  traceId: string | null;
}

/* ---------------- AI analytics ---------------- */

export interface AiModels {
  byTier: Record<string, number>;
  byProvider: Record<string, number>;
  byModel: Record<string, number>;
  tokensByProvider: Record<string, number>;
  costUsd: number | null;
}

export interface AiRouting {
  byStrategy: Record<string, number>;
  byTier: Record<string, number>;
  byReason: Record<string, number>;
  marginP50: number | null;
  marginP95: number | null;
}

export interface AiRag {
  searches: number;
  cacheHitRate: number | null;
  meanMs: number | null;
  similarityP50: number | null;
  similarityP95: number | null;
}

export interface AiCost {
  perModel: { model: string; tokens: number; costUsd: number | null }[];
  totalUsd: number | null;
}

/* ---------------- evaluation ---------------- */

export interface EvaluationSummary {
  guardBlockRate: number | null;
  clarificationRate: number | null;
  escalationRate: number | null;
  abortRate: number | null;
  latencyByTier: { tier: string; p50: number | null; p95: number | null }[];
  ragCacheHitRate: number | null;
  routing: { reason: string; count: number }[];
}

/* ---------------- system health ---------------- */

export interface HealthComponent {
  name: string;
  status: 'UP' | 'DOWN' | 'DEGRADED' | 'UNKNOWN' | string;
  detail: string | null;
  checkedAt: string;
}

export interface SystemHealth {
  status: string;
  components: HealthComponent[];
}
