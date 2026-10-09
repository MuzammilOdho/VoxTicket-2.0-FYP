/** Demo chat client: POST /api/v1/chat (dev/test profile). No auth. */
import { useState, useCallback } from 'react';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  turnNumber?: number;
  requiresConfirmation?: boolean;
  requiresVerification?: boolean;
  pending?: boolean;
  error?: boolean;
}

interface ChatResponse {
  sessionId: string;
  text: string;
  requiresVerification: boolean;
  requiresConfirmation: boolean;
  identityAssurance: string;
  turnNumber: number;
}

let msgSeq = 0;
const nextId = () => `m-${Date.now()}-${msgSeq++}`;

export function useDemoChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = useCallback(() => {
    setMessages([]);
    setSessionId(null);
    setError(null);
    setBusy(false);
  }, []);

  const send = useCallback(
    async (text: string, phone?: string) => {
      const trimmed = text.trim();
      if (!trimmed || busy) return;
      setBusy(true);
      setError(null);
      const userMsg: ChatMessage = { id: nextId(), role: 'user', text: trimmed };
      const pendingId = nextId();
      setMessages((m) => [...m, userMsg, { id: pendingId, role: 'assistant', text: '', pending: true }]);
      try {
        const res = await fetch('/api/v1/chat', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sessionId,
            customerPhone: phone?.trim() || undefined,
            message: trimmed,
          }),
        });
        if (!res.ok) {
          const t = await res.text().catch(() => '');
          throw new Error(t || `Chat failed (${res.status})`);
        }
        const data = (await res.json()) as ChatResponse;
        setSessionId(data.sessionId);
        setMessages((m) =>
          m.map((msg) =>
            msg.id === pendingId
              ? {
                  id: pendingId,
                  role: 'assistant' as const,
                  text: data.text,
                  turnNumber: data.turnNumber,
                  requiresConfirmation: data.requiresConfirmation,
                  requiresVerification: data.requiresVerification,
                }
              : msg,
          ),
        );
      } catch (e) {
        const msg = e instanceof Error ? e.message : 'Request failed';
        setError(msg);
        setMessages((m) =>
          m.map((x) =>
            x.id === pendingId ? { ...x, pending: false, error: true, text: 'The agent could not be reached. Is the backend running?' } : x,
          ),
        );
      } finally {
        setBusy(false);
      }
    },
    [busy, sessionId],
  );

  return { messages, sessionId, busy, error, send, reset };
}
