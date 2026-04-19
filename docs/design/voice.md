# Pulseboard — Voice

## Tone adjectives

- Technical-confident
- Plain-spoken
- Rhythm-aware (pulse metaphor carries through copy)
- No performative excitement

## Do

- Name the metric precisely ("p99 latency", not "how slow your app feels")
- Say the number + say what it means ("p99: 240ms — degraded")
- Lean into the heartbeat metaphor when it adds meaning, not as fluff
- Short sentences. Active voice. Verb-first.

## Don't

- Don't say "sync" when you mean "upload" (technical imprecision)
- Don't call it a dashboard — the SHEET is the dashboard; the app is the agent
- Don't apologize when the network is bad (that's what Pulseboard is FOR measuring)
- Don't use emoji in UI copy. (OK in marketing + sponsor CTAs.)

## Sample copy

### Hero tagline
> Your team's network heartbeat, every 15 minutes, in a Sheet you own.

### CTA (install / fork)
**Primary**: Install on team devices · **Secondary**: Fork + rebrand

### Error states
- No network: `Offline. Waiting to reconnect.`
- Upload failed: `Sheet didn't accept the upload. Check permissions.`
- Endpoint misconfigured: `Upload URL not set. Open Settings to point at your Apps Script Web App.`
- Battery optimizer killed service: `<OEM> battery optimizer stopped Pulseboard. Add exemption in Settings → Battery → Pulseboard → Don't optimize.`

### Marketing section intro
> The question every distributed team asks at 3pm:
> **"Is it my connection or theirs?"**
>
> Pulseboard answers that. Per device. Every 15 minutes. In a Google Sheet you already own.

### Install microcopy
**Title**: 3 steps to see your team's pulse
**Steps**:
1. Deploy the Apps Script receiver (one-time, 30s)
2. Install Pulseboard on employee devices
3. Open the Sheet in 30 minutes — you'll have data
