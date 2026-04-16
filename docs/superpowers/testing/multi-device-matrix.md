# Multi-Device Testing Matrix

**APK**: `app/build/outputs/apk/release/app-release.apk` (signed, 4.9 MB, v2-signed)
**Keystore SHA-256**: `d875b5b5799066f2601fbb6fc81798fa2e73fba1967e2d25a87e8c6057fd86f3`
**Distribution URL**: _(fill in after upload to Drive)_

## Objective

Verify the app runs reliably across the OEM skins and Android versions your team actually uses. The test is: **does a row land in the Sheet every 15 minutes for every device in the matrix?**

## Tester-side setup (2 minutes per device)

```
1. Download the APK from the share link.
2. Allow "install from unknown sources" when prompted.
3. Install → open → enter your @codingninjas.com email → Start Monitoring.
4. Allow notifications + allow background battery.
5. If on Xiaomi / OPPO / Vivo / Samsung:
   Settings → Apps → Manage apps → Network Monitor CN
     → Autostart = ON
     → Battery saver = No restrictions
   Open recents → long-press the Network Monitor card → tap 🔒 (lock)
6. Leave running. Don't swipe-clear from recents.
```

## Tracking matrix

Add a row per test device. Fill in columns as the device reports.

| Tester | Email used | Device | Android ver | OEM skin | Install ✓ | First row in Sheet? | samples_count after 1h | Issues |
|---|---|---|---|---|---|---|---|---|
| (you) | chinmay... | Redmi Note 11 (2201117PI) | 13 | MIUI 13 | ✅ | ✅ 17:45 IST | 253 → expected ~900 next | Swipe-kill setup done |
| | | | | | | | | |
| | | | | | | | | |
| | | | | | | | | |

## Target coverage

At minimum, verify one device on each of:

| Tier | Device family | Test priority |
|---|---|---|
| 1 | Xiaomi / Redmi / POCO (MIUI) | Highest — strictest OEM killer |
| 1 | OPPO / Realme / OnePlus (ColorOS / OxygenOS) | High — ColorOS is MIUI-class aggressive |
| 2 | Samsung (One UI) | Important — large user base |
| 2 | Vivo / iQOO (FuntouchOS) | Important — "high background power consumption" prompts |
| 3 | Google Pixel (AOSP/stock) | Baseline — confirms the app is correct before blaming OEM quirks |
| 3 | Motorola (near-stock) | Low risk |
| 4 | Nothing Phone | Low risk |

Android version spread: cover **API 26 (Android 8)** through **API 35 (Android 15)** if possible. Each jump brings small foreground-service and permission changes.

## What success looks like per device

**After 30 minutes of running:**
- 2 rows in the Sheet with that device's `user_id`
- Second row has `samples_count` in the 800–900 range (~full 15-min window)
- `packet_loss_pct` under 5% on normal WiFi

**After 1 hour:**
- 4 rows, all with `samples_count` ≈ 900

**After reboot (tester reboots their phone):**
- Next flush boundary after they unlock → a new row appears *without them opening the app*

## Red flags per device

| Observation | Likely cause | Fix |
|---|---|---|
| No row for >30 min on a device that onboarded | Service killed / didn't start | Open app once (OnboardingActivity re-starts service); enforce Autostart setting |
| `samples_count` < 500 consistently | OEM throttling the sampler coroutine | Enforce Autostart + battery "No restrictions"; lock in recents |
| `samples_count` between 500–800 | Minor throttling, tolerable for V1 | No action needed unless it drops further |
| Rows appear but `packet_loss_pct` high (>20%) | Real network issue on tester's WiFi/cellular | Not a bug — that's exactly what we wanted to detect |
| No row after reboot | `BootReceiver` blocked by OEM autostart policy | Tester must open app once after every reboot (document this); long-term fix: request more aggressive Autostart permission |

## OEM-specific notes

### Xiaomi / Redmi / POCO (MIUI / HyperOS)
- **Autostart** is OFF by default for all sideloaded apps. Must toggle on.
- May require Mi account sign-in to install non-Play Store apps. Alternative: region-switch to India/US.
- Lock in recents = mandatory, not optional.
- ~50% of MIUI devices ignore `stopWithTask=false`. Mitigation: OnboardingActivity re-starts service on every launch (already done).

### OPPO / Realme (ColorOS)
- Similar to MIUI. Settings → Battery → Floating energy-saving box → add app.
- "Startup manager" toggle per app.

### Vivo / iQOO (FuntouchOS / OriginOS)
- Settings → Battery → Background app management → Normal background → pick our app.
- "High background power consumption" warning prompts may appear over time — tester should dismiss with "Allow".

### Samsung (One UI)
- Settings → Device care → Battery → Background usage limits → Never sleeping apps → add.
- "Put unused apps to sleep" is auto-enabled — needs explicit exemption per app.
- Samsung's Device Health Services may occasionally force-close background apps.

### Google Pixel (stock AOSP)
- No gotchas. Battery exemption dialog during onboarding is enough.
- Useful control device: if Pixel works but Xiaomi doesn't, issue is MIUI-specific.

### Motorola / Nothing (near-stock)
- Same as Pixel.

## Diagnostics cheatsheet

If a tester reports no rows appearing:

1. Open the app. Does it launch? → No: OEM install lockout. Resolve via Settings.
2. Is the persistent notification visible in the shade? → No: Notification permission denied. Reinstall or grant in Settings → App → Notifications.
3. Is the `PingService` running? Ask tester to run `adb shell dumpsys activity services | grep PingService` (requires ADB — developer task, not user-facing).
4. Has any row *ever* landed for that email? → If yes: intermittent OEM kill. Pursue Autostart/battery hardening. If no: onboarding didn't complete successfully.

## Bulk-checking the Sheet (pivot suggestion)

In Google Sheets, build a pivot with:
- **Rows**: `user_id`
- **Values**: `COUNT(window_start)` (how many rows per user), `AVG(samples_count)`, `AVG(packet_loss_pct)`
- **Filter**: `window_start` > _today at 00:00 UTC_

Any row where COUNT is much less than expected (expected = hours running × 4 flushes/hr) = candidate for OEM troubleshooting.
