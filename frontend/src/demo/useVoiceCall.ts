/** Browser voice call via LiveKit, as a hook so the demo can render the
 *  call inline in the unified window (Gemini-style: one composer, mic
 *  toggles voice).
 *
 *  Feedback surfaced to the UI:
 *   - state: idle | mic | connecting | live | error
 *   - agentPresent: the Python voice worker has joined the room
 *   - speaking: 'you' | 'agent' | null via ActiveSpeakersChanged
 *   - elapsedSec: live call timer
 *   - networkTier: 'full' | 'low' | 'survival' (adaptive audio, see below)
 *
 *  Flow: GET /api/v1/voice/token?room=..[&identity=..] -> connect -> publish mic ->
 *  the Python voice agent (joined to the same LiveKit server) handles the
 *  call; agent audio plays through a hidden <audio> element.
 *
 *  Identity: the demo passes the backend-assigned customer's E.164 phone as
 *  the LiveKit participant identity. The worker reads it back from the room
 *  and forwards it as customerPhone on every voice turn, so voice and chat
 *  resolve the same customer. Without it the call still works, but the
 *  turns are anonymous (the pre-identity-sync behavior).
 *
 *  Adaptive audio (adaptiveAudio.ts): the mic is published at the 'low'
 *  tier by default — 12 kbps Opus mono + RED (packet-loss repair) + DTX —
 *  which sounds identical to 24 kbps for speech on fast links and survives
 *  slow ones. A 5s stats poll (packet loss / jitter / RTT from the mic
 *  sender's getStats) plus LiveKit's connection-quality events drive a
 *  hysteresis controller: step down fast on poor signal, step up slowly on
 *  sustained excellent signal, hold otherwise. Tier switches republish the
 *  mic (brief ~200ms gap, mute state preserved; the mic is restored on the
 *  previous tier if a republish fails) and are broadcast to the
 *  worker on the 'voxticket.network' data topic so it can apply matching
 *  patience (endpointing delays) per tier. 'survival' keeps the same 12
 *  kbps codec floor — there is nothing useful below it — and changes
 *  turn-taking instead.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConnectionQuality, Participant, Room, Track } from 'livekit-client';
import {
  NETWORK_BROADCAST_VERSION,
  NETWORK_TOPIC,
  TierController,
  encodeTierBroadcast,
  extractNetworkSample,
  publishOptionsForTier,
  qualityFromLiveKit,
  tierBadge,
  type AudioPresetPair,
  type AudioTier,
  type CounterSnapshot,
  type NetworkSample,
  type QualitySignal,
} from './adaptiveAudio';

export type VoiceCallState = 'idle' | 'mic' | 'connecting' | 'live' | 'error';
export type Speaking = 'you' | 'agent' | null;

interface TokenResponse {
  url: string;
  token: string;
  room: string;
  identity: string;
}

/** How often the mic sender's stats are sampled for the tier controller. */
const STATS_POLL_MS = 5000;
/** Tier broadcasts are heartbeated so a worker that joins late still learns it. */
const TIER_BROADCAST_MS = 30000;

export function useVoiceCall() {
  const [state, setState] = useState<VoiceCallState>('idle');
  const [detail, setDetail] = useState<string | null>(null);
  const [agentPresent, setAgentPresent] = useState(false);
  const [speaking, setSpeaking] = useState<Speaking>(null);
  const [muted, setMuted] = useState(false);
  const [elapsedSec, setElapsedSec] = useState(0);
  const [lastCallSec, setLastCallSec] = useState(0);
  const [networkTier, setNetworkTier] = useState<AudioTier>('low');
  const roomRef = useRef<Room | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const timerRef = useRef<number | null>(null);
  const elapsedRef = useRef(0);
  // Adaptive-audio refs (not state: they drive behavior; the tier mirrors to state for the badge).
  const audioCtlRef = useRef<TierController | null>(null);
  const audioPresetsRef = useRef<AudioPresetPair | null>(null);
  const statsPrevRef = useRef<CounterSnapshot | null>(null);
  const lastQualityRef = useRef<QualitySignal>('good');
  const lastSampleRef = useRef<NetworkSample | null>(null);
  const statsTimerRef = useRef<number | null>(null);
  const broadcastTimerRef = useRef<number | null>(null);
  const mutedRef = useRef(false);
  const trackSourceRef = useRef<{ Microphone: Track.Source } | null>(null);

  const stopTimer = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const stopAdaptive = useCallback(() => {
    if (statsTimerRef.current !== null) {
      window.clearInterval(statsTimerRef.current);
      statsTimerRef.current = null;
    }
    if (broadcastTimerRef.current !== null) {
      window.clearInterval(broadcastTimerRef.current);
      broadcastTimerRef.current = null;
    }
    audioCtlRef.current = null;
    audioPresetsRef.current = null;
    statsPrevRef.current = null;
    lastSampleRef.current = null;
    lastQualityRef.current = 'good';
  }, []);

  const cleanup = useCallback(() => {
    stopTimer();
    stopAdaptive();
    const room = roomRef.current;
    roomRef.current = null;
    if (room) room.disconnect();
    setAgentPresent(false);
    setSpeaking(null);
    setMuted(false);
    mutedRef.current = false;
    setNetworkTier('low');
    setLastCallSec(elapsedRef.current);
    elapsedRef.current = 0;
    setElapsedSec(0);
  }, [stopTimer, stopAdaptive]);

  useEffect(() => cleanup, [cleanup]);

  const attachAgentAudio = useCallback((room: Room) => {
    room.remoteParticipants.forEach((p) => {
      p.audioTrackPublications.forEach((pub) => {
        if (pub.track && audioRef.current) pub.track.attach(audioRef.current);
      });
    });
  }, []);

  /** Tell the worker the current tier (+ last measured stats) on the data channel. Fail-open. */
  const broadcastTier = useCallback((room: Room, tier: AudioTier) => {
    try {
      const s = lastSampleRef.current;
      const payload = encodeTierBroadcast({
        v: NETWORK_BROADCAST_VERSION,
        tier,
        lossPct: s?.lossPct ?? -1,
        jitterMs: s?.jitterMs ?? -1,
        rttMs: s?.rttMs ?? null,
        at: new Date().toISOString(),
      });
      void room.localParticipant.publishData(payload, {
        reliable: true,
        topic: NETWORK_TOPIC,
      });
    } catch {
      /* telemetry-style: never break the call */
    }
  }, []);

  /** Switch the mic to a tier's publish options. Preserves mute. Fail-open:
   *  if the republish fails, the mic is restored on the previous tier. */
  const applyTier = useCallback(async (room: Room, tier: AudioTier, prevTier: AudioTier) => {
    const presets = audioPresetsRef.current;
    const micSource = trackSourceRef.current;
    if (!presets || !micSource) return;
    const pubs = [...room.localParticipant.audioTrackPublications.values()];
    const localTrack = pubs.find((p) => p.track)?.track;
    const mediaTrack = localTrack?.mediaStreamTrack;
    if (!localTrack || !mediaTrack) return;
    const wasMuted = mutedRef.current;
    const opts = (t: AudioTier) => ({
      ...publishOptionsForTier(t, presets),
      source: micSource.Microphone,
    });
    let newPub;
    try {
      // stopOnUnpublish=false: the MediaStreamTrack is reused for the republish.
      await room.localParticipant.unpublishTrack(localTrack, false);
      newPub = await room.localParticipant.publishTrack(mediaTrack, opts(tier));
    } catch (e) {
      // The old publication is gone; get the mic back on the previous tier
      // rather than leave the caller silent. Tier stays unchanged.
      console.warn('[voice] audio tier switch failed, restoring previous tier', e);
      try {
        newPub = await room.localParticipant.publishTrack(mediaTrack, opts(prevTier));
      } catch {
        return; // mic stays down; the user can rejoin the call
      }
      try {
        if (wasMuted) await newPub.track?.mute();
      } catch { /* mute is best-effort here */ }
      return;
    }
    try {
      if (wasMuted) await newPub.track?.mute();
    } catch { /* mute is best-effort here */ }
    setNetworkTier(tier);
    broadcastTier(room, tier);
  }, [broadcastTier]);

  const start = useCallback(async (phone?: string) => {
    setState('mic');
    setDetail(null);
    try {
      // Mic permission first: fail fast with a clear message.
      // Explicit audio processing constraints: Chrome enables AEC/NS/AGC by
      // default for `{ audio: true }`, but stating them documents the
      // requirement and covers browsers where the defaults differ. These are
      // "ideal" (not "exact"): a browser lacking one keeps working. This is
      // the client-side half of echo/noise handling; the worker-side half is
      // the AEC warmup + adaptive interruption in python-voice/agent.py.
      // No extra server-side DSP (e.g. RNNoise) is warranted at FYP scope:
      // it would add latency and ops cost for no measurable quality gain
      // over this combination.
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });
      stream.getTracks().forEach((t) => t.stop());

      setState('connecting');
      // livekit-client stays out of the initial bundle — loaded on first call.
      const { Room: RoomCtor, RoomEvent, Track, AudioPresets } =
        await import('livekit-client');
      const roomName = `demo-${Date.now().toString(36)}`;
      // Identity = the backend-assigned customer's phone: minted into the
      // LiveKit JWT by our own /voice/token endpoint, read back by the
      // worker and forwarded as customerPhone on every voice turn.
      const tokenUrl =
        `/api/v1/voice/token?room=${encodeURIComponent(roomName)}` +
        (phone?.trim() ? `&identity=${encodeURIComponent(phone.trim())}` : '');
      const tres = await fetch(tokenUrl);
      if (!tres.ok) {
        throw new Error(`Token request failed (${tres.status}). Is the backend running with the dev profile?`);
      }
      const { url, token } = (await tres.json()) as TokenResponse;

      const room = new RoomCtor();
      roomRef.current = room;
      audioPresetsRef.current = AudioPresets;
      trackSourceRef.current = Track.Source;
      const ctl = new TierController();
      audioCtlRef.current = ctl;
      setNetworkTier(ctl.tier);

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

      // Adaptive audio: LiveKit's own quality signal votes immediately;
      // the 5s stats poll below votes with measured packet loss.
      room.on(
        RoomEvent.ConnectionQualityChanged,
        (quality: ConnectionQuality, participant: Participant) => {
          if (participant.sid !== room.localParticipant.sid) return; // adapt on the uplink
          const c = audioCtlRef.current;
          if (!c) return;
          const signal = qualityFromLiveKit(quality);
          lastQualityRef.current = signal;
          const prevTier = c.tier;
          const stepped = c.note(signal, c.lastLossPct);
          if (stepped) void applyTier(room, stepped, prevTier);
        },
      );

      await room.connect(url, token);
      // Default tier 'low': 12 kbps Opus + RED + DTX. Step up only on
      // sustained excellent network (controller hysteresis).
      await room.localParticipant.setMicrophoneEnabled(
        true,
        undefined,
        publishOptionsForTier(ctl.tier, AudioPresets),
      );
      refreshAgent();
      attachAgentAudio(room);

      // Stats poll: sample the mic sender, feed the controller, switch tiers.
      statsTimerRef.current = window.setInterval(() => {
        const r = roomRef.current;
        const c = audioCtlRef.current;
        if (!r || !c) return;
        void (async () => {
          try {
            const pub = [...r.localParticipant.audioTrackPublications.values()]
              .find((p) => p.track);
            const sender = pub?.track?.sender;
            if (!sender) return;
            const report = await sender.getStats();
            const extracted = extractNetworkSample(report, statsPrevRef.current);
            if (!extracted) return;
            statsPrevRef.current = extracted.snapshot;
            if (!extracted.sample) return; // first sample: no loss delta yet
            lastSampleRef.current = extracted.sample;
            const prevTier = c.tier;
            const stepped = c.note(lastQualityRef.current, extracted.sample.lossPct);
            if (stepped) await applyTier(r, stepped, prevTier);
          } catch {
            /* fail-open: a stats failure must never disturb the call */
          }
        })();
      }, STATS_POLL_MS);

      // Heartbeat the tier so a worker that joins late still learns it.
      broadcastTier(room, ctl.tier);
      broadcastTimerRef.current = window.setInterval(() => {
        const r = roomRef.current;
        const c = audioCtlRef.current;
        if (r && c) broadcastTier(r, c.tier);
      }, TIER_BROADCAST_MS);

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
  }, [attachAgentAudio, cleanup, applyTier, broadcastTier]);

  const toggleMute = useCallback(async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !muted;
    await room.localParticipant.setMicrophoneEnabled(!next);
    setMuted(next);
    mutedRef.current = next;
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
    networkTier, networkBadge: tierBadge(networkTier),
    start, hangup, toggleMute, dismissError, audioRef,
    active: state === 'live' || state === 'mic' || state === 'connecting',
  };
}

export function formatElapsed(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}
