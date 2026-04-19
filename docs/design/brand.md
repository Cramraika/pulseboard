# Pulseboard — Brand

## Name
**Pulseboard** (pulse = network heartbeat sample; board = Google Sheet dashboard it uploads to)

## One-liner
Your team's network heartbeat, delivered to a Google Sheet every 15 minutes.

## Story
Born internal at Coding Ninjas to triage "why do VoIP calls drop every afternoon?" Uploads latency/jitter/packet-loss aggregates once every 15 minutes per employee device. Open-sourced 2026-04-19 so any distributed team can fork + rebrand.

## Mission
Make network quality boringly observable. Zero dashboard drama, zero gatekeeping — the Sheet IS the dashboard.

## Visual voice
- **Technical confidence** — teams who buy this are sysadmins; they want clarity over warmth
- **Heartbeat metaphor** — pulse wave, rhythmic, steady
- **Dark-mode-first** — IT teams run dark mode; mobile app follows device theme

## Audience
- **Primary**: IT/DevOps teams at small-to-mid companies (20–500 employees)
- **Secondary**: Solo operators / tech-savvy individuals
- **Forkers**: OSS consumers who rebrand for their own team

## Competitors / differentiation

| Competitor | Differentiation |
|---|---|
| PingPlotter / WinMTR | Active-probe tools for single devices. Pulseboard is passive-aggregate-across-team. |
| Nagios / Zabbix / Prometheus | Infrastructure monitoring. Pulseboard is end-user experience monitoring (from the user's phone out). |
| Cloudflare Radar | Network-wide public data. Pulseboard is YOUR team's private data. |
| RUM vendors (DataDog, Sentry) | Require SDK in your app. Pulseboard is a ghost agent on the device. |

## Positioning statement
For distributed teams whose sales calls drop at 3pm, Pulseboard is the zero-config Android agent that uploads network health to a Sheet you own. Unlike Cloudflare Radar or vendor RUMs, it gives you *your employees' reality*, not aggregated noise.
