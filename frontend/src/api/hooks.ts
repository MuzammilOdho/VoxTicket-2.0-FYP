import { QueryClient, keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from './client';
import type {
  AiCost,
  AiModels,
  AiRag,
  AiRouting,
  AuditEvent,
  ConversationDetail,
  ConversationSummary,
  EvaluationSummary,
  OperationRow,
  Page,
  SummaryV2,
  SystemHealth,
  TurnTrace,
  VoiceCall,
  VoiceCallDetail,
} from './types';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      retryDelay: 500,
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      // Stage 5: a filter/page change keeps the previous page on screen
      // while the new one loads (QueryState shows a quiet "Updating…"
      // line instead of blanking the page). Detail queries benefit too.
      placeholderData: keepPreviousData,
    },
  },
});

const ADMIN = '/api/v1/admin';

/**
 * Phase 1 (admin rework): filter dates are `yyyy-MM-dd` days from a date
 * input. The backend window is [from, to) on instants, so a from-date
 * becomes that day at 00:00 UTC and a to-date becomes the *next* day at
 * 00:00 UTC (exclusive), making the to-date itself included. A value that
 * is already a full instant passes through unchanged.
 */
export function dateToInstant(date: string | undefined, endOfDay: boolean): string | undefined {
  if (!date) return undefined;
  if (date.length > 10) return date;
  if (endOfDay) {
    const d = new Date(`${date}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + 1);
    return d.toISOString();
  }
  return `${date}T00:00:00.000Z`;
}

function setWindow(p: URLSearchParams, from?: string, to?: string) {
  const f = dateToInstant(from, false);
  const t = dateToInstant(to, true);
  if (f) p.set('from', f);
  if (t) p.set('to', t);
}

export interface ConversationFilters {
  channel?: string;
  outcome?: string;
  status?: string;
  escalated?: string;
  q?: string;
  from?: string;
  to?: string;
  page: number;
  size: number;
}

export function useSummary(from?: string, to?: string) {
  const p = new URLSearchParams();
  setWindow(p, from, to);
  const qs = p.toString();
  return useQuery({
    queryKey: ['summary', from ?? '', to ?? ''],
    queryFn: () => api.get<SummaryV2>(`${ADMIN}/summary/v2${qs ? `?${qs}` : ''}`),
  });
}

export function useConversations(f: ConversationFilters) {
  const p = new URLSearchParams();
  if (f.channel) p.set('channel', f.channel);
  if (f.outcome) p.set('outcome', f.outcome);
  if (f.status) p.set('status', f.status);
  if (f.escalated) p.set('escalated', f.escalated);
  if (f.q) p.set('q', f.q);
  if (f.from) p.set('from', f.from);
  if (f.to) p.set('to', f.to);
  p.set('page', String(f.page));
  p.set('size', String(f.size));
  return useQuery({
    queryKey: ['conversations', f],
    queryFn: () => api.get<Page<ConversationSummary>>(`${ADMIN}/conversations/search?${p}`),
    placeholderData: (prev) => prev,
  });
}

export function useConversationDetail(sessionId: string) {
  return useQuery({
    queryKey: ['conversation', sessionId],
    queryFn: () => api.get<ConversationDetail>(`${ADMIN}/conversations/${encodeURIComponent(sessionId)}/detail`),
    enabled: sessionId.length > 0,
  });
}

export function useTurnTrace(traceId: string | null | undefined, enabled: boolean, turnNumber?: number) {
  return useQuery({
    queryKey: ['turn', traceId, turnNumber ?? null],
    queryFn: () => api.get<TurnTrace>(
      `${ADMIN}/turns/${encodeURIComponent(traceId as string)}${turnNumber != null ? `?turnNumber=${turnNumber}` : ''}`),
    enabled: enabled && !!traceId,
    staleTime: 60_000,
  });
}

export interface OperationFilters {
  page: number;
  size: number;
  q?: string;
}

export function useOperations(kind: string, f: OperationFilters) {
  const p = new URLSearchParams({ page: String(f.page), size: String(f.size) });
  if (f.q) p.set('q', f.q);
  return useQuery({
    queryKey: ['operations', kind, f],
    queryFn: () => api.get<Page<OperationRow>>(`${ADMIN}/operations/${kind}?${p}`),
    placeholderData: (prev) => prev,
  });
}

export function useVoiceCalls(page: number, size: number, outcome?: string, from?: string, to?: string) {
  const p = new URLSearchParams({ page: String(page), size: String(size) });
  if (outcome) p.set('outcome', outcome);
  setWindow(p, from, to);
  return useQuery({
    queryKey: ['voice-calls', page, size, outcome ?? '', from ?? '', to ?? ''],
    queryFn: () => api.get<Page<VoiceCall>>(`${ADMIN}/voice/calls?${p}`),
    placeholderData: (prev) => prev,
  });
}

/** Returns null when the session has no voice call record (chat sessions). Never throws for 404. */
export function useVoiceCallDetail(sessionId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['voice-call', sessionId],
    queryFn: async (): Promise<VoiceCallDetail | null> => {
      try {
        return await api.get<VoiceCallDetail>(`${ADMIN}/voice/calls/${encodeURIComponent(sessionId)}`);
      } catch {
        return null;
      }
    },
    enabled: enabled && sessionId.length > 0,
    staleTime: 60_000,
  });
}

export interface AuditFilters {
  sessionId?: string;
  type?: string;
  from?: string;
  to?: string;
  page: number;
  size: number;
}

export function useAuditEvents(f: AuditFilters) {
  const p = new URLSearchParams({ page: String(f.page), size: String(f.size) });
  if (f.sessionId) p.set('sessionId', f.sessionId);
  if (f.type) p.set('type', f.type);
  if (f.from) p.set('from', f.from);
  if (f.to) p.set('to', f.to);
  return useQuery({
    queryKey: ['audit', f],
    queryFn: () => api.get<Page<AuditEvent>>(`${ADMIN}/audit/events?${p}`),
    placeholderData: (prev) => prev,
  });
}

export function useAiModels(from?: string, to?: string) {
  const p = new URLSearchParams();
  setWindow(p, from, to);
  const qs = p.toString();
  return useQuery({
    queryKey: ['ai-models', from ?? '', to ?? ''],
    queryFn: () => api.get<AiModels>(`${ADMIN}/ai/models${qs ? `?${qs}` : ''}`),
  });
}

export function useAiRouting(from?: string, to?: string) {
  const p = new URLSearchParams();
  setWindow(p, from, to);
  const qs = p.toString();
  return useQuery({
    queryKey: ['ai-routing', from ?? '', to ?? ''],
    queryFn: () => api.get<AiRouting>(`${ADMIN}/ai/routing${qs ? `?${qs}` : ''}`),
  });
}

export function useAiRag(from?: string, to?: string) {
  const p = new URLSearchParams();
  setWindow(p, from, to);
  const qs = p.toString();
  return useQuery({
    queryKey: ['ai-rag', from ?? '', to ?? ''],
    queryFn: () => api.get<AiRag>(`${ADMIN}/ai/rag${qs ? `?${qs}` : ''}`),
  });
}

export function useAiCost(from?: string, to?: string) {
  const p = new URLSearchParams();
  setWindow(p, from, to);
  const qs = p.toString();
  return useQuery({
    queryKey: ['ai-cost', from ?? '', to ?? ''],
    queryFn: () => api.get<AiCost>(`${ADMIN}/ai/cost${qs ? `?${qs}` : ''}`),
  });
}

export function useEvaluation(from?: string, to?: string) {
  const p = new URLSearchParams();
  setWindow(p, from, to);
  const qs = p.toString();
  return useQuery({
    queryKey: ['evaluation', from ?? '', to ?? ''],
    queryFn: () => api.get<EvaluationSummary>(`${ADMIN}/evaluation/summary${qs ? `?${qs}` : ''}`),
  });
}

export function useSystemHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: () => api.get<SystemHealth>(`${ADMIN}/system/health`),
    refetchInterval: 30_000,
  });
}
