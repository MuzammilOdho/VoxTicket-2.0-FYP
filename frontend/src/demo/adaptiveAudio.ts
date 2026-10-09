/** Adaptive audio tiers for the VoxTicket demo voice call.
 *
 *  One call setup for both fast and very slow internet. The insight: for
 *  *speech*, 12 kbps Opus mono with redundancy (RED) and DTX sounds the
 *  same as 24 kbps on a fast link and survives a slow one — so 'low' is the
 *  always-on default and 'full' is only a step-up reward for sustained
 *  excellent networks. 'survival' keeps the same 12 kbps codec floor (there
 *  is nothing useful below it) and instead tells the Python worker to get
 *  patient: longer endpointing, longer transcription timeouts, gentler
 *  interruption handling.
 *
 *  All LiveKit values come from the caller (the dynamic import in
 *  useVoiceCall.ts) so this module stays unit-testable and out of the
 *  initial bundle. Only `import type` from 'livekit-client' is used here —
 *  type imports are erased at build time.
 */
import type {
  AudioPreset,
  ConnectionQuality,
  TrackPublishOptions,
} from 'livekit-client';

/** Audio quality tiers, best -> worst. */
export type AudioTier = 'full' | 'low' | 'survival';

/** Data-channel topic the demo uses to tell the worker the current tier. */
export const NETWORK_TOPIC = 'voxticket.network';

/** Broadcast protocol version (bumped if the JSON schema changes). */
export const NETWORK_BROADCAST_VERSION = 1;

// ---------------------------------------------------------------------------
// Tier -> LiveKit publish options
// ---------------------------------------------------------------------------

/** The two presets we switch between; passed in from the livekit-client import. */
export interface AudioPresetPair {
  telephone: AudioPreset; // 12 kbps
  speech: AudioPreset; // 24 kbps
}

/**
 * LiveKit publish options for a tier. RED (redundant audio data) rides
 * along in every packet so one lost packet is rebuilt from its neighbour
 * with no retransmit round-trip; DTX silences the encoder when nobody is
 * speaking. Both default on for mono tracks in livekit-client, but we set
 * them explicitly so a future SDK default change can't silently drop them.
 */
export function publishOptionsForTier(
  tier: AudioTier,
  presets: AudioPresetPair,
): TrackPublishOptions {
  const audioPreset = tier === 'full' ? presets.speech : presets.telephone;
  return {
    audioPreset,
    dtx: true,
    red: true,
    forceStereo: false,
  };
}

// ---------------------------------------------------------------------------
// Network measurement -> tier state machine
// ---------------------------------------------------------------------------

/** Coarse signal, mapped from LiveKit's ConnectionQuality. */
export type QualitySignal = 'excellent' | 'good' | 'poor' | 'lost';

export function qualityFromLiveKit(q: ConnectionQuality): QualitySignal {
  switch (q) {
    case 'excellent':
      return 'excellent';
    case 'good':
      return 'good';
    case 'poor':
      return 'poor';
    case 'lost':
      return 'lost';
    default:
      return 'good'; // 'unknown' and future values: hold, don't flap
  }
}

/** Packet-loss % at or above this counts as a poor observation. */
export const LOSS_POOR_PCT = 5;
/** Packet-loss % below this (with excellent quality) counts as good. */
export const LOSS_GOOD_PCT = 1;
/** Consecutive poor observations before stepping one tier down. */
export const POOR_STREAK_TO_STEP_DOWN = 2;
/** Consecutive good observations before stepping one tier up. */
export const GOOD_STREAK_TO_STEP_UP = 6;

/**
 * Hysteresis controller: steps down fast (a couple of bad 5s polls),
 * steps up slow (half a minute of clean signal). Never flaps on a single
 * sample, and 'good' (not excellent, not poor) always holds the tier.
 *
 * Starts at 'low' — the safe default that works on any network.
 */
export class TierController {
  private _tier: AudioTier = 'low';
  private _poorStreak = 0;
  private _goodStreak = 0;
  /** Most recent loss reading, exposed so quality events can vote with it. */
  lastLossPct = 0;

  get tier(): AudioTier {
    return this._tier;
  }

  /**
   * Record one network observation. Returns the new tier when a step
   * occurs, otherwise null (tier unchanged).
   */
  note(signal: QualitySignal, lossPct: number): AudioTier | null {
    this.lastLossPct = lossPct;
    const poor = signal === 'poor' || signal === 'lost' || lossPct >= LOSS_POOR_PCT;
    const good = signal === 'excellent' && lossPct < LOSS_GOOD_PCT;
    if (poor) {
      this._poorStreak += 1;
      this._goodStreak = 0;
    } else if (good) {
      this._goodStreak += 1;
      this._poorStreak = 0;
    } else {
      this._poorStreak = 0;
      this._goodStreak = 0;
    }

    if (this._poorStreak >= POOR_STREAK_TO_STEP_DOWN && this._tier !== 'survival') {
      this._tier = this._tier === 'full' ? 'low' : 'survival';
      this._poorStreak = 0;
      this._goodStreak = 0;
      return this._tier;
    }
    if (this._goodStreak >= GOOD_STREAK_TO_STEP_UP && this._tier !== 'full') {
      this._tier = this._tier === 'survival' ? 'low' : 'full';
      this._poorStreak = 0;
      this._goodStreak = 0;
      return this._tier;
    }
    return null;
  }
}

// ---------------------------------------------------------------------------
// getStats() -> loss / jitter / RTT
// ---------------------------------------------------------------------------

export interface CounterSnapshot {
  packetsSent: number;
  packetsLost: number;
}

export interface NetworkSample {
  /** Percent of sent packets lost since the previous sample. */
  lossPct: number;
  /** Mean jitter in ms (from remote-inbound-rtp). */
  jitterMs: number;
  /** Current round-trip time in ms, null when unavailable. */
  rttMs: number | null;
}

/**
 * Extract a network sample from an RTCRtpSender's stats report.
 * Loss is delta-based between consecutive samples so a long call doesn't
 * dilute recent loss. Returns null when no audio outbound-rtp is present
 * yet; the first call returns a snapshot with lossPct 0 (no delta yet).
 */
export function extractNetworkSample(
  report: RTCStatsReport,
  prev: CounterSnapshot | null,
): { sample: NetworkSample | null; snapshot: CounterSnapshot } | null {
  let packetsSent = 0;
  let packetsLost = 0;
  let jitterMs = 0;
  let rttMs: number | null = null;
  let sawOutbound = false;

  report.forEach((s) => {
    if (s.type === 'outbound-rtp' && (s as { kind?: string }).kind === 'audio') {
      sawOutbound = true;
      packetsSent = (s as { packetsSent?: number }).packetsSent ?? 0;
    } else if (s.type === 'remote-inbound-rtp') {
      packetsLost = (s as { packetsLost?: number }).packetsLost ?? 0;
      jitterMs = ((s as { jitter?: number }).jitter ?? 0) * 1000;
    } else if (s.type === 'candidate-pair' && (s as { nominated?: boolean }).nominated) {
      const rtt = (s as { currentRoundTripTime?: number }).currentRoundTripTime;
      if (typeof rtt === 'number') rttMs = rtt * 1000;
    }
  });

  if (!sawOutbound) return null;
  const snapshot: CounterSnapshot = { packetsSent, packetsLost };
  if (!prev) return { sample: null, snapshot };

  const sentDelta = packetsSent - prev.packetsSent;
  const lostDelta = Math.max(0, packetsLost - prev.packetsLost);
  const lossPct = sentDelta > 0 ? (lostDelta / sentDelta) * 100 : 0;
  return { sample: { lossPct, jitterMs, rttMs }, snapshot };
}

// ---------------------------------------------------------------------------
// Tier broadcast (demo -> worker over the LiveKit data channel)
// ---------------------------------------------------------------------------

export interface TierBroadcast {
  v: number;
  tier: AudioTier;
  lossPct: number;
  jitterMs: number;
  rttMs: number | null;
  /** ISO-8601, worker-local clock of the sender. */
  at: string;
}

/** JSON schema the Python worker decodes; keep in sync with agent.py. */
export function encodeTierBroadcast(b: TierBroadcast): Uint8Array<ArrayBuffer> {
  // TextEncoder always returns an ArrayBuffer-backed view; the cast
  // satisfies livekit-client's publishData(data: Uint8Array<ArrayBuffer>).
  return new TextEncoder().encode(JSON.stringify(b)) as Uint8Array<ArrayBuffer>;
}

export function decodeTierBroadcast(data: Uint8Array): TierBroadcast | null {
  try {
    const raw = JSON.parse(new TextDecoder().decode(data)) as Partial<TierBroadcast>;
    if (raw.v !== NETWORK_BROADCAST_VERSION) return null;
    if (raw.tier !== 'full' && raw.tier !== 'low' && raw.tier !== 'survival') return null;
    return {
      v: raw.v,
      tier: raw.tier,
      lossPct: typeof raw.lossPct === 'number' ? raw.lossPct : -1,
      jitterMs: typeof raw.jitterMs === 'number' ? raw.jitterMs : -1,
      rttMs: typeof raw.rttMs === 'number' ? raw.rttMs : null,
      at: typeof raw.at === 'string' ? raw.at : '',
    };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Badge
// ---------------------------------------------------------------------------

export interface TierBadge {
  label: string;
  tone: 'ok' | 'info' | 'warn';
  /** One-line explainer for the tooltip. */
  hint: string;
}

export function tierBadge(tier: AudioTier): TierBadge {
  switch (tier) {
    case 'full':
      return {
        label: 'Full quality',
        tone: 'ok',
        hint: 'Excellent network: 24 kbps speech audio.',
      };
    case 'low':
      return {
        label: 'Data-saver',
        tone: 'info',
        hint: '12 kbps Opus + packet-loss repair. Sounds the same for speech, survives slow links.',
      };
    case 'survival':
      return {
        label: 'Survival mode',
        tone: 'warn',
        hint: 'Poor network: the agent waits longer before replying so choppy audio is not cut off.',
      };
  }
}
