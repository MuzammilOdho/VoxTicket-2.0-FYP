import { QueryClient, useQuery } from '@tanstack/react-query';
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
    },
  },
});

const ADMIN = '/api/v1/admin';

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

export function useSummary() {
  return useQuery({ queryKey: ['summary'], queryFn: () => api.get<SummaryV2>(`${ADMIN}/summary/v2`) });
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

export function useTurnTrace(traceId: string | null | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['turn', traceId],
    queryFn: () => api.get<TurnTrace>(`${ADMIN}/turns/${encodeURIComponent(traceId as string)}`),
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

export function useVoiceCalls(page: number, size: number, outcome?: string) {
  const p = new URLSearchParams({ page: String(page), size: String(size) });
  if (outcome) p.set('outcome', outcome);
  return useQuery({
    queryKey: ['voice-calls', page, size, outcome ?? ''],
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

export function useAiModels() {
  return useQuery({ queryKey: ['ai-models'], queryFn: () => api.get<AiModels>(`${ADMIN}/ai/models`) });
}

export function useAiRouting() {
  return useQuery({ queryKey: ['ai-routing'], queryFn: () => api.get<AiRouting>(`${ADMIN}/ai/routing`) });
}

export function useAiRag() {
  return useQuery({ queryKey: ['ai-rag'], queryFn: () => api.get<AiRag>(`${ADMIN}/ai/rag`) });
}

export function useAiCost() {
  return useQuery({ queryKey: ['ai-cost'], queryFn: () => api.get<AiCost>(`${ADMIN}/ai/cost`) });
}

export function useEvaluation() {
  return useQuery({ queryKey: ['evaluation'], queryFn: () => api.get<EvaluationSummary>(`${ADMIN}/evaluation/summary`) });
}

export function useSystemHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: () => api.get<SystemHealth>(`${ADMIN}/system/health`),
    refetchInterval: 30_000,
  });
}
