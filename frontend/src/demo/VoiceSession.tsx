/** Browser voice session via LiveKit.
 *
 * Flow: GET /api/v1/voice/token?room=.. -> connect with livekit-client ->
 * publish microphone -> the Python voice agent (must be running and joined
 * to the same LiveKit server) handles the call. Agent audio plays through
 * an <audio> element bound to the agent's track.
 *
 * States are explicit: idle, requesting mic, connecting, live, error.
 * If the agent never joins, the UI shows "waiting for agent" instead of
 * failing silently.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { Room, RoomEvent, Track } from 'livekit-client';
import { Badge, Button, ErrorBox } from '../ui/primitives';

export type VoiceState = 'idle' | 'mic' | 'connecting' | 'live' | 'error';

interface TokenResponse {
  url: string;
  token: string;
  room: string;
  identity: string;
}

export function VoiceSession({ personaName }: { personaName: string }) {
  const [state, setState] = useState<VoiceState>('idle');
  const [detail, setDetail] = useState<string | null>(null);
  const [agentPresent, setAgentPresent] = useState(false);
  const [muted, setMuted] = useState(false);
  const roomRef = useRef<Room | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  const cleanup = useCallback(() => {
    const room = roomRef.current;
    roomRef.current = null;
    if (room) room.disconnect();
    setAgentPresent(false);
    setMuted(false);
  }, []);

  useEffect(() => cleanup, [cleanup]);

  const attachAgentAudio = useCallback((room: Room) => {
    room.remoteParticipants.forEach((p) => {
      p.audioTrackPublications.forEach((pub) => {
        if (pub.track && audioRef.current) {
          pub.track.attach(audioRef.current);
        }
      });
    });
  }, []);

  const start = useCallback(async () => {
    setState('mic');
    setDetail(null);
    try {
      // Mic permission first: fail fast with a clear message.
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.getTracks().forEach((t) => t.stop());

      setState('connecting');
      const roomName = `demo-${Date.now().toString(36)}`;
      const tres = await fetch(`/api/v1/voice/token?room=${encodeURIComponent(roomName)}`);
      if (!tres.ok) throw new Error(`Token request failed (${tres.status}). Is the backend running with the dev profile?`);
      const { url, token } = (await tres.json()) as TokenResponse;

      const room = new Room();
      roomRef.current = room;

      room.on(RoomEvent.ParticipantConnected, () => {
        setAgentPresent(room.remoteParticipants.size > 0);
        attachAgentAudio(room);
      });
      room.on(RoomEvent.ParticipantDisconnected, () => {
        setAgentPresent(room.remoteParticipants.size > 0);
      });
      room.on(RoomEvent.TrackSubscribed, (track) => {
        if (track.kind === Track.Kind.Audio && audioRef.current) {
          track.attach(audioRef.current);
        }
        setAgentPresent(true);
      });
      room.on(RoomEvent.Disconnected, () => {
        setState('idle');
        setAgentPresent(false);
      });

      await room.connect(url, token);
      await room.localParticipant.setMicrophoneEnabled(true);
      setAgentPresent(room.remoteParticipants.size > 0);
      attachAgentAudio(room);
      setState('live');
    } catch (e) {
      cleanup();
      setState('error');
      setDetail(e instanceof Error ? e.message : 'Could not start the voice session');
    }
  }, [attachAgentAudio, cleanup]);

  const toggleMute = useCallback(async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !muted;
    await room.localParticipant.setMicrophoneEnabled(!next);
    setMuted(next);
  }, [muted]);

  const hangup = useCallback(() => {
    cleanup();
    setState('idle');
    setDetail(null);
  }, [cleanup]);

  return (
    <div className="flex h-full flex-col">
      <audio ref={audioRef} autoPlay playsInline className="hidden" />

      <div className="flex flex-1 flex-col items-center justify-center gap-6 p-6">
        {state === 'idle' && (
          <>
            <VoiceOrb mode="idle" />
            <div className="max-w-sm text-center">
              <div className="text-[15px] font-medium text-ink">Voice session as {personaName}</div>
              <p className="mt-2 text-sm leading-relaxed text-ink-mute">
                Your microphone connects to the VoxTicket voice agent over LiveKit.
                The agent must be running — start <span className="font-mono text-ink-dim">python-voice/agent.py</span> first.
              </p>
            </div>
            <Button variant="primary" onClick={start} className="!px-8 !py-3">Start voice session</Button>
          </>
        )}

        {(state === 'mic' || state === 'connecting') && (
          <>
            <VoiceOrb mode="busy" />
            <div className="text-sm text-ink-mute">{state === 'mic' ? 'Requesting microphone…' : 'Connecting to LiveKit…'}</div>
          </>
        )}

        {state === 'live' && (
          <>
            <VoiceOrb mode={agentPresent ? 'live' : 'waiting'} />
            <div className="flex items-center gap-3">
              <Badge tone={agentPresent ? 'signal' : 'mute'} pulse={agentPresent}>
                {agentPresent ? 'Agent connected' : 'Waiting for agent'}
              </Badge>
              {muted && <Badge tone="warn">Muted</Badge>}
            </div>
            {!agentPresent && (
              <p className="max-w-sm text-center text-sm text-ink-mute">
                Connected to the room, but the voice agent hasn't joined yet. Make sure the Python
                voice worker is running against the same LiveKit server.
              </p>
            )}
            <div className="flex gap-3">
              <Button variant="ghost" onClick={toggleMute}>{muted ? 'Unmute' : 'Mute'}</Button>
              <Button variant="danger" onClick={hangup}>End call</Button>
            </div>
            <p className="max-w-sm text-center text-xs leading-relaxed text-ink-mute">
              Speak naturally — you can interrupt the agent at any time. Try switching to Urdu mid-call.
            </p>
          </>
        )}

        {state === 'error' && (
          <div className="w-full max-w-sm">
            <ErrorBox message={detail ?? 'Could not start the voice session'} onRetry={start} />
            <div className="mt-3 text-center">
              <button onClick={() => setState('idle')} className="text-sm text-ink-mute hover:text-ink">Back</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function VoiceOrb({ mode }: { mode: 'idle' | 'busy' | 'waiting' | 'live' }) {
  if (mode === 'live') {
    // Animated bars while the call is live.
    return (
      <div className="flex h-16 items-center gap-1.5" aria-hidden>
        {[0, 1, 2, 3, 4, 5, 6].map((i) => (
          <span
            key={i}
            className="vq-bar w-1.5 bg-signal"
            style={{ height: 44, animationDelay: `${i * 0.12}s` }}
          />
        ))}
      </div>
    );
  }
  const ring =
    mode === 'busy' || mode === 'waiting'
      ? 'border-signal/60'
      : 'border-line';
  return (
    <div className={`flex h-20 w-20 items-center justify-center rounded-full border-2 ${ring}`} aria-hidden>
      <span className={`vq-pulse h-3 w-3 rounded-full ${mode === 'idle' ? 'bg-ink-mute' : 'bg-signal'}`} />
    </div>
  );
}
