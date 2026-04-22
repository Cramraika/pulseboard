# Pulseboard Privacy Policy

_Last updated: 2026-04-22. Version: v0.1._

This policy covers **Pulseboard** (`com.vagarylabs.pulseboard`), the open-source Android application published by Vagary Labs.

## What Pulseboard does

Pulseboard is a network diagnostics utility. When installed, it samples your device's connection quality (latency, packet loss, jitter, Wi-Fi metadata) and sends periodic aggregates to a Google Sheet **that you or your team configures and owns**. Pulseboard does not operate a backend server; there is no Pulseboard-operated cloud.

## Data we collect — at a glance

| Data | When | Where it goes | Why |
|---|---|---|---|
| Network quality metrics (RTT, loss, jitter) | Every sample interval while app is running | Your configured Sheets/webhook endpoint | Core functionality |
| Wi-Fi metadata (BSSID, SSID, RSSI, channel) | Every sample interval | Your configured endpoint | Correlate quality with access points |
| Visible-AP list (with permission) | Every sample interval | Your configured endpoint | Detect sticky-client issues |
| Device OS version / model | Once per install | Your configured endpoint | Differentiate device behavior |
| User-chosen identifier (email hash recommended) | Once at onboarding | Your configured endpoint | Per-user pivoting in your Sheet |

## What we do NOT collect

- Contents of your network traffic (Pulseboard measures connection quality, not what you browse)
- Contacts, photos, calendar, SMS, microphone, camera
- Advertising identifiers
- Precise GPS location (only Wi-Fi BSSID, which is coarse at best)
- Data from other apps

## Data sharing

Pulseboard does **not** share your data with Vagary Labs, Google (beyond the Play Store install), or any third party. All measurement data flows only to the upload endpoint that *you or your IT admin* configured during setup. If you are a fork-user running your own deployment, data goes only to your endpoint.

Your data is **not sold**, **not monetized**, and **not used for advertising** under any circumstance.

## Permissions we request and why

- **Location (ACCESS_FINE_LOCATION)** — Android system-wide policy: required to read Wi-Fi BSSID and scan results. Pulseboard never reads GPS coordinates.
- **Internet + Network State** — to send samples and detect connectivity transitions.
- **Foreground Service** — to maintain continuous sampling on supported Android versions.
- **Boot Completed** — to resume monitoring after device restart (optional; can be disabled).
- **Battery optimization exemption (prompted)** — to prevent OEM battery savers from killing the sampling service.

## Data retention

Pulseboard stores nothing server-side (we have no server). On-device, a short rolling buffer of unsent samples is kept until the next successful upload — typically minutes.

How long your data persists in your uploaded Sheet is entirely under your control. Pulseboard sends data; you own the Sheet.

## Security

Uploads go over HTTPS to your configured endpoint. On-device storage uses the standard Android application sandbox.

## Your rights

Because Pulseboard stores no data on behalf of Vagary Labs, rights-of-access and rights-of-deletion must be exercised against the endpoint operator (your IT admin if installed by your employer; yourself if self-hosted).

To remove Pulseboard's on-device state: uninstall the app.

## Open source

Pulseboard's complete source code is available under the MIT License at https://github.com/Cramraika/pulseboard. You can verify every claim in this policy by reading the source.

## Children's use

Pulseboard is not directed to children under 13. We do not knowingly collect data from children.

## Changes to this policy

Material changes to this policy will be announced in the GitHub repository and, when the v1.1 engine wires up data collection, surfaced in the app's onboarding flow.

## Contact

- Issues: https://github.com/Cramraika/pulseboard/issues
- Email: *[Please replace with the maintainer contact email before publishing]*

---

_Vagary Labs is the maintainer umbrella. Pulseboard is released under the MIT License._
