/** Live captions of the caller's speech during a demo voice call.
 *
 *  Uses the Web Speech API (Chromium) against the already-granted microphone.
 *  This transcribes what the CALLER says, locally in the browser — it is not
 *  the agent's transcript (the voice pipeline's transcripts stay server-side
 *  and the browser has no access to them). Labeled honestly in the UI.
 *  Unsupported browsers get `supported === false` and the transcript pane
 *  simply shows call events instead.
 */
import { useEffect, useRef, useState } from 'react';

export interface Caption {
  id: string;
  text: string;
  at: Date;
  final: boolean;
}

interface SpeechRecognitionResultLike {
  isFinal: boolean;
  [index: number]: { transcript: string };
}

export function useLiveCaptions(active: boolean) {
  const [captions, setCaptions] = useState<Caption[]>([]);
  const [supported, setSupported] = useState(true);
  const recRef = useRef<{ stop: () => void } | null>(null);
  const activeRef = useRef(active);
  const idRef = useRef(0);
  activeRef.current = active;

  useEffect(() => {
    if (!active) {
      try {
        recRef.current?.stop();
      } catch {
        /* already stopped */
      }
      recRef.current = null;
      return;
    }

    const w = window as unknown as Record<string, unknown>;
    const SR =
      (w.SpeechRecognition as new () => unknown) ??
      (w.webkitSpeechRecognition as new () => unknown);
    if (!SR) {
      setSupported(false);
      return;
    }
    setSupported(true);

    const rec = new SR() as unknown as {
      continuous: boolean;
      interimResults: boolean;
      lang: string;
      onresult: ((e: { resultIndex: number; results: SpeechRecognitionResultLike[] }) => void) | null;
      onerror: (() => void) | null;
      onend: (() => void) | null;
      start: () => void;
      stop: () => void;
    };
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = 'en-US';

    let interimId: string | null = null;
    rec.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        const text = (r[0]?.transcript ?? '').trim();
        if (!text) continue;
        if (r.isFinal) {
          const id = `cap-${idRef.current++}`;
          setCaptions((c) => [...c.filter((x) => x.id !== interimId), { id, text, at: new Date(), final: true }]);
          interimId = null;
        } else {
          if (!interimId) interimId = `cap-interim-${idRef.current++}`;
          const id = interimId;
          setCaptions((c) => [...c.filter((x) => x.id !== id), { id, text, at: new Date(), final: false }]);
        }
      }
    };
    rec.onerror = () => {
      /* keep the call usable; captions just stop */
    };
    rec.onend = () => {
      // Chrome stops recognition on silence — restart while the call is live.
      if (activeRef.current && recRef.current) {
        try {
          rec.start();
        } catch {
          /* already started */
        }
      }
    };

    try {
      rec.start();
    } catch {
      /* already started */
    }
    recRef.current = rec;

    return () => {
      try {
        rec.stop();
      } catch {
        /* already stopped */
      }
      if (recRef.current === rec) recRef.current = null;
    };
  }, [active]);

  const clear = () => setCaptions([]);

  return { captions, supported, clear };
}
