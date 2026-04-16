# Network Monitor CN

Internal Android app that continuously samples network quality on employee devices and logs aggregated metrics to a Google Sheet. Built for Coding Ninjas' internal monitoring — side-loaded, no Google Play publication.

![platform](https://img.shields.io/badge/platform-Android%208.0+-brightgreen)
![distribution](https://img.shields.io/badge/distribution-signed%20APK-blue)
![status](https://img.shields.io/badge/status-v1-green)

---

## What it does

Runs silently in the background on a user's phone, pings `8.8.8.8` once per second, and every 15 minutes uploads a single aggregate row to a Google Sheet containing:

| metric | meaning |
|---|---|
| **avg / min / max** ping (ms) | Baseline latency signals |
| **p50 / p95 / p99** ping (ms) | Tail-latency — p99 is the key VoIP signal |
| **jitter** (ms) | Population stddev; >30 ms breaks most VoIP jitter buffers |
| **packet loss** (%) | Fraction of pings that timed out or failed |
| **samples_count** | How many samples fed the aggregates; <900 = OEM throttled |
| **max_rtt_offset_sec** | When in the window the worst spike happened |
| **device_model, network_type, user_id** | Device + user identification |

One row per device every 15 minutes. The Sheet becomes a time-series of each employee's network quality.

## Why it exists

- **VoIP quality triage**: "Why do my calls drop every afternoon?" — compare the user's rows against call logs.
- **Home WiFi vs office LAN vs cellular**: see baseline avg/jitter across setups to guide hardware purchases.
- **Incident post-mortems**: when multiple employees report issues simultaneously, their rows show whether it was a network problem (packet_loss spikes) or app-specific (no change in rows).
- **OEM quirks**: the `samples_count` column doubles as a health-check — if it drops below ~900 consistently for a user, their phone is throttling background apps.

## How it works (one-paragraph architecture)

`OnboardingActivity` collects an `@codingninjas.com` email once, then starts `PingService` — an Android foreground service running a `SupervisorJob` scope with two coroutines: a **sampler** at 1 Hz calling `PingEngine.runPing(target=8.8.8.8)`, and a **flusher** aligned to wall-clock quarter-hours (`:00/:15/:30/:45`) that drains the sample buffer, runs `MetricsCalculator.aggregate(...)` (linear-interpolation percentiles, population stddev jitter), and POSTs the result to a Google Apps Script webhook that appends a row to the Sheet. A retain-on-failure buffer (bounded at 5400 samples = 90 min) survives network outages and merges retained samples into the next successful flush. `BootReceiver` restarts the service after device reboot. `MainActivity` is a read-only dashboard that renders the last-flush metrics from `SharedPreferences` — no live refresh.

## Full design

- **Spec**: [`docs/superpowers/specs/2026-04-16-network-monitor-design.md`](docs/superpowers/specs/2026-04-16-network-monitor-design.md) (13 sections covering architecture, data model, algorithms, failure modes, risks, future work)
- **Implementation plan**: [`docs/superpowers/plans/2026-04-16-network-monitor.md`](docs/superpowers/plans/2026-04-16-network-monitor.md) (17-task TDD plan with complete code, tests, and commit messages)
- **Testing matrix**: [`docs/superpowers/testing/multi-device-matrix.md`](docs/superpowers/testing/multi-device-matrix.md) (per-OEM setup guidance + field results tracker)

## Installation — for end users

You'll get an APK link from your admin.

1. Download the APK. On first install, allow **"Install from unknown sources"** for your browser / Drive app when prompted.
2. Open the APK → tap **Install** (Android may warn "unknown developer" — expected, the app is internal).
3. Open **Network Monitor** → enter your `@codingninjas.com` email → tap **Start Monitoring**.
4. When prompted, **Allow notifications** and **Allow always in background** (both required).

### OEM-specific hardening (MUST do on Xiaomi / Realme / Vivo / OPPO / Samsung)

Android OEM skins kill background apps aggressively. Do **all three** per device:

- **Autostart**: Settings → Apps → Manage apps → Network Monitor CN → **Autostart ON** (or "Auto-launch" on ColorOS)
- **Battery**: same screen → **Battery** → **No restrictions** / **Unrestricted** / **Allow background activity**
- **Lock in recents**: open recents (square button) → long-press the Network Monitor card → tap the lock icon

Without these steps the service survives ~1 to 4 hours then gets silently killed.

Google Pixel, Motorola, Nothing: no extra steps needed — stock Android respects foreground services.

## Installation — for maintainers (building + releasing)

### Prerequisites
- Android Studio (any recent version)
- JDK 17+ (Android Studio's bundled JBR works)
- Gradle wrapper (committed)

### Signing keystore

A release keystore is required to build signed APKs. Generate once with `keytool`:

```
keytool -genkey -v \
  -keystore ~/keystores/networkmonitor-release.jks \
  -alias networkmonitor \
  -keyalg RSA -keysize 2048 -validity 10000
```

Add these four properties to `~/.gradle/gradle.properties` (outside the repo):

```
KEYSTORE_PATH=/Users/you/keystores/networkmonitor-release.jks
KEYSTORE_PASSWORD=<your strong password>
KEY_ALIAS=networkmonitor
KEY_PASSWORD=<your strong password>
```

**Save the password in a password manager. Losing it means the app can never be updated on existing installs.**

### Build

```
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (~4.9 MB, v2-signed).

Verify the signature:

```
$ANDROID_HOME/build-tools/<version>/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

### Run unit tests

```
./gradlew testDebugUnitTest
```

26 tests: `SampleBuffer` (8), `MetricsCalculator` (11), `SheetsUploader` (7).

### Distribute

Upload `app-release.apk` to Google Drive → right-click → **Get link** → "Anyone with the link" → copy. Share the link with your internal users along with the installation steps above.

## Google Apps Script — backend webhook

The Sheet is fed by an Apps Script bound to the Sheet. Code is copied in spec §9. Deployed as Web App with "Execute as: me", "Who has access: Anyone" (including anonymous).

To verify: open the `/exec` URL in a browser — should display `"Network Monitor webhook is live."`.

## Project structure

```
app/src/main/java/com/codingninjas/networkmonitor/
├── Constants.kt              # All magic numbers / keys / URLs
├── PingEngine.kt             # Wraps the system ping command
├── NetworkUtils.kt           # WiFi / Mobile / Ethernet detection
├── NotificationHelper.kt     # Foreground notification channel + builder
├── MetricsCalculator.kt      # Aggregate: avg/min/max/p50/p95/p99/jitter/loss
├── SampleBuffer.kt           # Thread-safe bounded sample accumulator
├── SheetsUploader.kt         # HTTP POST with dual-gate success check
├── OnboardingActivity.kt     # Email-gated onboarding (@codingninjas.com)
├── BootReceiver.kt           # Restarts service on BOOT_COMPLETED
├── service/
│   └── PingService.kt        # Foreground service + sampler + flusher coroutines
└── ui/
    └── MainActivity.kt       # Read-only dashboard

app/src/main/res/
├── layout/
│   ├── activity_onboarding.xml
│   └── activity_main.xml
├── drawable/
│   ├── ic_launcher_background.xml    # Brand purple #4F46E5
│   ├── ic_launcher_foreground.xml    # White WiFi arcs + dot
│   └── ic_notification.xml           # Notification tray icon
├── values/themes.xml                 # Theme.MaterialComponents.Light
└── mipmap-anydpi/ic_launcher.xml     # Adaptive icon glue

app/src/test/java/com/codingninjas/networkmonitor/
├── SampleBufferTest.kt               # 8 JVM tests
├── MetricsCalculatorTest.kt          # 11 JVM tests
└── SheetsUploaderTest.kt             # 7 MockWebServer tests
```

## Field-test results (as of v1.0 release)

Tested on 5 real devices across 4 OEM skins and 3 Android versions:

| Device | Android | Skin | Result |
|---|---|---|---|
| Redmi Note 11 (Xiaomi 2201117PI) | 13 (API 33) | MIUI 13 | Works. Drops to ~70% duty cycle if OEM hardening not applied. |
| Realme 7 4G (RMX3031) | 13 (API 33) | realme UI 4 | ~100% duty cycle after hardening. |
| Realme 10 Pro-class (RMX3690) | 12 (API 31) | realme UI 4 | Retain-on-failure validated against real 13-min WiFi outage. |
| Samsung Galaxy F22 (SM-E225F) | 13 (API 33) | One UI 5 | ~100% duty cycle, clean. |
| POCO (2312BPC51H) | 15 (API 35) | HyperOS | ~100% duty cycle. Android 15 foreground-service policies fully compatible. |

**Key insight**: `samples_count` divided by window duration (seconds) gives you a real **duty cycle** metric. Anything >= 0.95 = OEM is behaving. Below 0.9 = OEM throttling; users should re-apply the hardening steps.

## Known limitations (shipped deliberately)

These are deferred future work, not bugs:

- **Dashboard doesn't live-update.** Only refreshes when the Activity resumes. If you reopen the app to see a new row, tap home then return.
- **No in-app update checker.** Distribute new APKs manually; users must reinstall.
- **Single ping target (`8.8.8.8`).** If your network blocks Google DNS, metrics will show 100% loss even when other destinations are reachable. Future: multi-target (Google + Cloudflare + internal gateway).
- **No raw per-sample storage.** Only 15-min aggregates are kept. For forensic deep-dives on specific incidents, this is too coarse. Future enhancement: upload a daily CSV of raw samples to Google Drive alongside the Sheet.
- **Webhook is "Anyone" access.** Anyone with the URL can POST arbitrary rows. Acceptable for internal use; harden with a shared secret if exposure widens.
- **Sheet fills eventually.** Google Sheets caps at 10M cells. Current schema: 15 cols × 96 rows/day/user ≈ 1440 cells/day/user. 10 users × 365 days ≈ 5.3M cells/year. Monitor and rotate the Sheet annually.

## License & ownership

Internal Coding Ninjas project. Not for external distribution.

Maintainer: Chinmay Ramraika (`chinmay.ramraika@codingninjas.com`).

## Change history

- **v1.0 (Apr 2026)** — Initial release. Foreground-service + wall-clock-aligned flushing architecture. Tested on 5 devices. Distributed as signed side-loaded APK.
