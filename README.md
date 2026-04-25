# Pulseboard

> **Background Android service that samples internet quality on your team's phones and uploads 15-min aggregates to your own Google Sheet. Open-source, fork-friendly, install-and-forget.**

[![GitHub Sponsors](https://img.shields.io/github/sponsors/Cramraika?logo=github&label=Sponsor)](https://github.com/sponsors/Cramraika)
[![Stars](https://img.shields.io/github/stars/Cramraika/pulseboard?style=social)](https://github.com/Cramraika/pulseboard/stargazers)
[![License](https://img.shields.io/github/license/Cramraika/pulseboard)](./LICENSE)
[![platform](https://img.shields.io/badge/platform-Android%208.0+-brightgreen)](https://developer.android.com)
[![Issues](https://img.shields.io/github/issues/Cramraika/pulseboard)](https://github.com/Cramraika/pulseboard/issues)

When your team complains about "the WiFi feels slow" or "calls drop in the afternoon", you don't have data — you have anecdote. **Pulseboard** runs silently as a foreground service on each user's Android phone, pings configurable network targets every second, and uploads compact 15-minute aggregates (avg / p50 / p95 / p99 RTT, jitter, loss, sample count, Wi-Fi context) to your own Google Sheet. The Sheet becomes the time-series of every employee's network quality — pivot by hour, by user, by Wi-Fi, by network type — and you finally have the receipts.

Designed to "install once, never touch again." Side-load today; Google Play candidate.

## Who is this for?

- **IT teams** who want continuous, distributed evidence of WiFi / VoIP / cellular quality without paying for an enterprise MDM
- **Operations leads** triaging "why are calls failing every Wednesday at 3 PM?" — Pulseboard data tells you per-user, per-hour, per-network whether it's the user, the AP, or the ISP
- **Distributed teams** comparing home WiFi vs office LAN vs cellular across 5-300 employees
- **Tinkerers** who want a fork-friendly Kotlin codebase to drop their own ping logic, target list, or Sheet schema into

## 💖 Sponsor this project

If Pulseboard saves you a finger-pointing meeting with your ISP or your office IT, [sponsor on GitHub](https://github.com/sponsors/Cramraika). Your support funds:

- **Google Play Store distribution** (pipeline is built; Play Console review pending)
- **Multi-target sampling** (currently single ping target per build; v1.1 adds 4-target parallel)
- **Per-sample CSV upload** (raw samples to Drive, not just 15-min aggregates) for forensic deep-dives
- **iOS port** — biggest single gap; sponsor-funded

Or [reach out](https://chinmayramraika.in) about enterprise support, white-label deployments, or consulting on a network you're trying to fix.

## What gets recorded

One row per device every 15 minutes, appended to your Google Sheet:

| Column | Meaning |
|---|---|
| `avg / min / max ping (ms)` | Baseline latency signals |
| `p50 / p95 / p99 ping (ms)` | Tail latency — p99 is the key VoIP signal |
| `jitter (ms)` | Population stddev; >30 ms breaks most VoIP jitter buffers |
| `packet_loss (%)` | Fraction of pings that timed out |
| `samples_count` | How many samples fed the aggregates; <900 = OEM throttled |
| `max_rtt_offset_sec` | When in the window the worst spike happened |
| `device_model`, `network_type`, `user_id` | Who + where |

The Sheet becomes a per-user-per-hour-per-network time-series. Pivot it however you want; no proprietary dashboard required.

## How it works

`OnboardingActivity` collects an email once (a domain gate is configurable), then starts `PingService` — an Android foreground service running a `SupervisorJob` scope with two coroutines:

- a **sampler** at 1 Hz calling `PingEngine.runPing(target)`
- a **flusher** aligned to wall-clock quarter-hours (`:00/:15/:30/:45`) that drains the sample buffer, runs `MetricsCalculator.aggregate(...)` (linear-interpolation percentiles, population-stddev jitter), and POSTs the result to a Google Apps Script webhook that appends a row to a Sheet you control.

A retain-on-failure buffer (bounded; ~90 min) survives network outages and merges retained samples into the next successful flush. `BootReceiver` restarts the service after device reboot. `MainActivity` is a read-only dashboard that renders the last-flush metrics from `SharedPreferences`.

Tested on Xiaomi (MIUI), Realme (realme UI), Samsung (One UI), POCO (HyperOS), and stock Android — see Field-test results below.

## 🚀 Install (end users)

You'll get an APK link from your admin (or a future Play Store link).

1. Download the APK. Allow **"Install from unknown sources"** for your browser / Drive app when prompted.
2. Open the APK → tap **Install**.
3. Open **Pulseboard** → enter your email → tap **Start Monitoring**.
4. When prompted, **Allow notifications** and **Allow always in background** (both required).

### OEM-specific hardening (MUST do on Xiaomi / Realme / Vivo / OPPO / Samsung)

Android OEM skins kill background apps aggressively. Do **all three** per device:

- **Autostart**: Settings → Apps → Manage apps → Pulseboard → **Autostart ON** (or "Auto-launch" on ColorOS)
- **Battery**: same screen → **Battery** → **No restrictions** / **Unrestricted** / **Allow background activity**
- **Lock in recents**: open recents → long-press the Pulseboard card → tap the lock icon

Without these, the service survives ~1 to 4 hours then gets silently killed.

Google Pixel, Motorola, Nothing: no extra steps — stock Android respects foreground services.

## 🚀 Install (maintainers — fork & deploy your own)

### Prerequisites
- Android Studio (any recent version)
- JDK 17+ (Android Studio's bundled JBR works)
- Gradle wrapper (committed)

### Configure your own webhook

Pulseboard uploads to a Google Apps Script you deploy. The script appends one row per POST to a Sheet you create.

1. Create a fresh Google Sheet
2. Tools → Script editor → paste the contents of `docs/apps-script/Code.gs` (provided)
3. Deploy → New deployment → type "Web App" → execute as "Me", access "Anyone"
4. Copy the `/exec` URL
5. Edit `app/src/main/java/.../Constants.kt` → set `WEBHOOK_URL = "<your-exec-url>"`

For organisation-internal builds, also edit:

- `Constants.ALLOWED_EMAIL_DOMAIN` (defaults to `@example.com`)
- `Constants.SMARTFLO_IP` / target IPs (defaults to public anycast endpoints)

### Signing keystore

Generate once:

```bash
keytool -genkey -v -keystore ~/keystores/pulseboard.jks \
  -alias pulseboard -keyalg RSA -keysize 2048 -validity 10000
```

Add to `~/.gradle/gradle.properties` (outside the repo):

```
PULSEBOARD_KEYSTORE_PATH=/Users/you/keystores/pulseboard.jks
PULSEBOARD_KEYSTORE_PASSWORD=<your strong password>
PULSEBOARD_KEY_ALIAS=pulseboard
PULSEBOARD_KEY_PASSWORD=<your strong password>
```

Save the password in a password manager. **Losing it means the app can never be updated on existing installs.**

### Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (~5 MB, v2-signed).

### Test

```bash
./gradlew testDebugUnitTest
```

`SampleBuffer`, `MetricsCalculator`, `SheetsUploader` — all unit-tested.

### Distribute

Side-load: upload `app-release.apk` to Google Drive → "Anyone with the link" → share. For Google Play, see [`RELEASING.md`](./RELEASING.md) — the full Play Store publishing pipeline (signed AAB upload, store listing, screenshots, declarations) is automated via `~/.claude/scripts/google-play-publisher.py`.

## Field-test results

| Device | Android | OEM Skin | Result |
|---|---|---|---|
| Redmi Note 11 (Xiaomi 2201117PI) | 13 (API 33) | MIUI 13 | Works. Drops to ~70% duty cycle if OEM hardening not applied. |
| Realme 7 4G (RMX3031) | 13 (API 33) | realme UI 4 | ~100% duty cycle after hardening. |
| Realme 10 Pro-class (RMX3690) | 12 (API 31) | realme UI 4 | Retain-on-failure validated against a real 13-min WiFi outage. |
| Samsung Galaxy F22 (SM-E225F) | 13 (API 33) | One UI 5 | ~100% duty cycle, clean. |
| POCO (2312BPC51H) | 15 (API 35) | HyperOS | ~100% duty cycle. Android 15 foreground-service policies fully compatible. |

**Key insight**: `samples_count` divided by window duration (seconds) gives you a real **duty cycle** metric. ≥ 0.95 = OEM is behaving. Below 0.9 = OEM throttling; users should re-apply the hardening steps.

## Known limitations (shipped deliberately)

- **Dashboard doesn't live-update.** Only refreshes when the Activity resumes.
- **No in-app update checker.** Distribute new APKs manually until Play Store launch.
- **Single ping target by default.** If your network blocks the configured target, metrics show 100% loss. Multi-target sampling is on the roadmap.
- **No raw per-sample storage.** Only 15-min aggregates upload. Daily raw-CSV-to-Drive is on the roadmap.
- **Webhook is "Anyone" access.** Anyone with your URL can POST arbitrary rows. Acceptable for internal use; harden with a shared secret if exposure widens.
- **Google Sheet capacity.** 10M-cell ceiling. Default schema: ~1440 cells/day/user. Rotate annually for org-scale deployments.

## Related projects

- **[pulseboard-desktop](https://github.com/Cramraika/pulseboard-desktop)** — Windows companion. While Pulseboard catches *when* and *who* sees degradation from phones, pulseboard-desktop runs MTR / tshark / iperf3 / speedtest from a laptop to find *why* and *where on the ISP path*. Use them together for the complete picture.
- **[bulk](https://github.com/Cramraika/bulk)** / **[tldv_downloader](https://github.com/Cramraika/tldv_downloader)** — other Vagary Labs OSS utilities.

## License

MIT. See [LICENSE](./LICENSE). Fork it, white-label it, deploy it for your team.

## Author

Built by [Chinmay Ramraika](https://chinmayramraika.in) under the **Vagary Labs OSS Utilities** umbrella. Originally developed internally to triage VoIP call drops; rebased public on 2026-04-19 because the tool is generally useful and the world doesn't have a great free option for distributed network-quality monitoring on Android.
