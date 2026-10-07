/** Browser voice call via LiveKit, as a hook so the demo can render the
 *  call inline in the unified window (Gemini-style: one composer, mic
 *  toggles voice).
 *
 *  Feedback surfaced to the UI:
 *   - state: idle | mic | connecting | live | error
 *   - agentPresent: the Python voice worker has joined the room
 *   - speaking: 'you' | 'agent' | null via ActiveSpeakersChanged
 *   - elapsedSec: live call timer
 *
 *  Flow: GET /api/v1/voice/token?room=.. -> connect -> publish mic ->
 *  the Python voice agent (joined to the same LiveKit server) handles the
 *  call; agent audio plays through a hidden <audio> element.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Participant, Room } from 'livekit-client';

export type VoiceCallState = 'idle' | 'mic' | 'connecting' | 'live' | 'error';
export type Speaking = 'you' | 'agent' | null;

interface TokenResponse {
  url: string;
  token: string;
  room: string;
  identity: string;
}

export function useVoiceCall() {
  const [state, setState] = useState<VoiceCallState>('idle');
  const [detail, setDetail] = useState<string | null>(null);
  const [agentPresent, setAgentPresent] = useState(false);
  const [speaking, setSpeaking] = useState<Speaking>(null);
  const [muted, setMuted] = useState(false);
  const [elapsedSec, setElapsedSec] = useState(0);
  const [lastCallSec, setLastCallSec] = useState(0);
  const roomRef = useRef<Room | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const timerRef = useRef<number | null>(null);
  const elapsedRef = useRef(0);

  const stopTimer = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const cleanup = useCallback(() => {
    stopTimer();
    const room = roomRef.current;
    roomRef.current = null;
    if (room) room.disconnect();
    setAgentPresent(false);
    setSpeaking(null);
    setMuted(false);
    setLastCallSec(elapsedRef.current);
    elapsedRef.current = 0;
    setElapsedSec(0);
  }, [stopTimer]);

  useEffect(() => cleanup, [cleanup]);

  const attachAgentAudio = useCallback((room: Room) => {
    room.remoteParticipants.forEach((p) => {
      p.audioTrackPublications.forEach((pub) => {
        if (pub.track && audioRef.current) pub.track.attach(audioRef.current);
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
      // livekit-client stays out of the initial bundle — loaded on first call.
      const { Room: RoomCtor, RoomEvent, Track } = await import('livekit-client');
      const roomName = `demo-${Date.now().toString(36)}`;
      const tres = await fetch(`/api/v1/voice/token?room=${encodeURIComponent(roomName)}`);
      if (!tres.ok) {
        throw new Error(`Token request failed (${tres.status}). Is the backend running with the dev profile?`);
      }
      const { url, token } = (await tres.json()) as TokenResponse;

      const room = new RoomCtor();
      roomRef.current = room;

      const refreshAgent = () => setAgentPresent(room.remoteParticipants.size > 0);
      const refreshSpeaking = (speakers: Participant[]) => {
        if (speakers.includes(room.localParticipant)) setSpeaking('you');
        else if (speakers.length > 0) setSpeaking('agent');
        else setSpeaking(null);
      };

      room.on(RoomEvent.ParticipantConnected, () => {
        refreshAgent();
        attachAgentAudio(room);
      });
      room.on(RoomEvent.ParticipantDisconnected, refreshAgent);
      room.on(RoomEvent.ActiveSpeakersChanged, refreshSpeaking);
      room.on(RoomEvent.TrackSubscribed, (track) => {
        if (track.kind === Track.Kind.Audio && audioRef.current) track.attach(audioRef.current);
        refreshAgent();
      });
      room.on(RoomEvent.Disconnected, () => {
        cleanup();
        setState('idle');
      });

      await room.connect(url, token);
      await room.localParticipant.setMicrophoneEnabled(true);
      refreshAgent();
      attachAgentAudio(room);
      setState('live');
      timerRef.current = window.setInterval(() => {
        elapsedRef.current += 1;
        setElapsedSec(elapsedRef.current);
      }, 1000);
    } catch (e) {
      cleanup();
      setState('error');
      setDetail(e instanceof Error ? e.message : 'Could not start the voice call');
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

  const dismissError = useCallback(() => {
    setState('idle');
    setDetail(null);
  }, []);

  return {
    state, detail, agentPresent, speaking, muted, elapsedSec, lastCallSec,
    start, hangup, toggleMute, dismissError, audioRef,
    active: state === 'live' || state === 'mic' || state === 'connecting',
  };
}

export function formatElapsed(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}
