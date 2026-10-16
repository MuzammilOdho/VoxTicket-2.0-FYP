# VoxTicket voice: SIP telephony setup (P3.3)

Take real PSTN phone calls through the VoxTicket voice worker. The worker
code is already wired: set `SIP_TRUNK_ID` and the worker logs the trunk at
startup, normalizes the caller's E.164 identity, and publishes telephony
metadata on its room participant. What this doc covers is everything
*outside* the worker: the Twilio elastic SIP trunk, the LiveKit SIP trunk,
and the dispatch rule that routes inbound calls to this worker.

> **Live verification needs your Twilio account.** Nothing here can be
> tested end-to-end without one. Every step below is marked
> **[twilio]** / **[livekit]** / **[worker]** so you know where it happens.

## Architecture

```
PSTN caller --SIP/RTP--> Twilio elastic SIP trunk --SIP--> LiveKit SIP service
    --WebRTC--> this worker (SIP_TRUNK_ID set)
    --HTTPS--> VoxTicket Java brain (/api/v1/voice/turn/stream)
```

The LiveKit SIP service answers the inbound call, creates a LiveKit room,
and dispatches this worker into it. The worker sees a normal LiveKit
session: the caller's phone number arrives as the remote participant's
`identity` (E.164), which the worker verifies/normalizes before forwarding
as `customerPhone` to the Java brain. Non-E.164 identities are rejected
with a clear warning and the call continues as unknown - identity is never
required to take the call.

## Step-by-step

### 1. [twilio] Create the elastic SIP trunk

1. Twilio Console -> Elastic SIP Trunking -> Create new SIP trunk
   (name it e.g. `voxticket-inbound`).
2. On the trunk: **Origination** -> add an Origination URI pointing at
   your LiveKit SIP endpoint:
   `sip:<your-livekit-sip-domain>` (LiveKit Cloud: find it under
   Settings -> SIP; self-hosted: your SIP service address).
3. **Termination** (only needed if the worker ever dials out - not used
   in P3; skip unless you add outbound later).
4. Note the trunk's **SID** - you will not need it in the worker, but you
   need it for Twilio-side debugging.

### 2. [livekit] Create the SIP trunk (inbound)

In the LiveKit dashboard (or via the API / `lk` CLI):

- Create an **inbound SIP trunk** pointing at Twilio. The trunk needs
  Twilio's IP ranges / credentials per the LiveKit SIP docs - LiveKit
  Cloud provides a guided "Twilio elastic trunk" flow; self-hosted, set
  it in the SIP service config.
- Note the trunk's **trunk ID** (looks like `ST_...`). This is your
  `SIP_TRUNK_ID`.

### 3. [livekit] Create the dispatch rule

The dispatch rule decides which LiveKit room an inbound call lands in
and which agent gets dispatched:

- Rule type: **Direct** (or Pin, if you want a demo PIN).
- Room name: pick a stable pattern, e.g. `sip-{caller}` or a fixed room
  per campaign. The worker reuses the room name as the VoxTicket
  conversation `sessionId`, so one call = one conversation session.
- Agent dispatch: point at this worker's agent name (the name the worker
  registers with when it runs `agent.py start`).
- Note the rule's **name** - this is your `SIP_DISPATCH_RULE`
  (informational: the worker logs it and publishes it as room metadata;
  the rule itself is enforced server-side).

### 4. [worker] Configure the environment

```bash
SIP_TRUNK_ID=ST_xxxxxxxxxxxxxxxx      # from step 2
SIP_DISPATCH_RULE=voxticket-inbound  # from step 3 (informational)
```

Everything else is unchanged. With `SIP_TRUNK_ID` empty the worker
behaves exactly as the WebRTC demo (the SIP branch is inert - no extra
logs, no metadata, no API calls).

### 5. [twilio] Point a phone number at the trunk

Twilio Console -> Phone Numbers -> pick/buy a number -> set its
**SIP Trunk** to the trunk from step 1. Callers dial this number.

## Test-call checklist

- [ ] Worker starts: log line `SIP telephony enabled: trunk_id=ST_...`
      appears once per startup (not per WebRTC call - WebRTC calls log
      nothing SIP-related).
- [ ] Dial the Twilio number from a real phone. You hear the VoxTicket
      greeting (the same `GREETING` as the demo).
- [ ] Ask for an order status / say an OTP digit sequence out loud.
      The Java brain receives `customerPhone` = your E.164 caller ID
      (check the brain's turn log).
- [ ] Barge in mid-reply: the preemptive turn lifecycle (P1) works the
      same on SIP as on WebRTC.
- [ ] Hang up: the call-end telemetry records `disconnectReason` and
      the room closes; no worker errors in the log.
- [ ] Call from a withheld/private number: the worker logs
      `rejecting non-E.164 caller identity ...; continuing as unknown`
      and the call still works (anonymous turn, as today).
- [ ] With `SIP_TRUNK_ID` unset, run the WebRTC demo: no SIP log lines,
      no behavior change.

## Troubleshooting

- **Call reaches Twilio but never rings the worker**: the dispatch rule
  (step 3) isn't matching - check the rule's trunk filter and room-name
  pattern in the LiveKit dashboard's SIP logs.
- **Worker joins but hears nothing**: check the LiveKit SIP region vs
  your worker's region (media hairpins across regions add latency), and
  confirm `SIP_TRUNK_ID` matches the trunk the call arrived on.
- **Caller ID shows as unknown**: Twilio didn't pass P-Asserted-Identity
  (withheld number or trunk config) - expected; the worker degrades to
  anonymous. The demo's phone-number identity requirement is a *demo*
  concern, not a telephony one.

## Out of scope for P3

Outbound dialing (Twilio termination on the trunk + a dial-out API),
call recording consent prompts (see the Java-side P3 recording work),
and multi-trunk routing. The worker hook points are in place; each is a
small, separate change.
