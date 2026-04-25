---
layout: default
title: Pulseboard
---

# Pulseboard

Install-and-forget network health vitals for distributed teams.

Open-source Android utility that samples internet quality (latency, jitter, packet loss, Wi-Fi context) on employee devices and uploads 15-minute aggregates to a Google Sheet you own.

- **Source**: [github.com/Cramraika/pulseboard](https://github.com/Cramraika/pulseboard)
- **License**: MIT
- **Privacy policy**: [play/PRIVACY](play/PRIVACY.html)
- **Release info**: [RELEASING](../RELEASING.html)

## Desktop companion

Pulseboard the Android app shows you *when* your team's network degrades and *who* sees it. The desktop companion finds *why* and *where on the ISP path*:

> **[pulseboard-desktop](https://github.com/Cramraika/pulseboard-desktop)** — Windows PowerShell diagnostic that runs MTR / tshark / iperf3 / speedtest side-by-side for hours. 40+ preflight checks, hop-level loss tracking, single-file packet capture, real-throughput baseline. Local-only, MIT, sponsor-ready.

Use them together: phones sample continuously to surface the *symptom*, the desktop tool runs deep-dive captures during bad windows to surface the *cause*.

Part of the Vagary Labs OSS utilities.
