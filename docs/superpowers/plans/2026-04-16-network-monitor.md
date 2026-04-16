# Network Monitor CN Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an internal-use Android app that samples network RTT every second, aggregates into 15-minute windows with rich percentile metrics, and POSTs each window as one row to a Google Sheet — distributed as a side-loaded signed APK.

**Architecture:** Foreground `PingService` runs two coroutines (sampler @ 1 Hz, flusher @ wall-clock quarter-hours) over a `SupervisorJob + Dispatchers.IO` scope. Onboarding is email-gated (`@codingninjas.com`); MainActivity is a read-only dashboard of last-flush metrics persisted in `SharedPreferences`. Pure logic (`MetricsCalculator`, `SampleBuffer`, `SheetsUploader`) is JVM-unit-tested; Service + Activities are manually verified against §11 of the spec.

**Tech Stack:** Kotlin 2.2, Android SDK 26–36, `AppCompatActivity` + Material Components, `OkHttp 4.12`, `Gson 2.10`, `kotlinx.coroutines 1.7`, JUnit 4 + OkHttp `MockWebServer` for tests. No Jetpack Compose.

**Spec:** `docs/superpowers/specs/2026-04-16-network-monitor-design.md`

**Pre-existing files to preserve verbatim:** `PingEngine.kt`, `NetworkUtils.kt`.

---

## Task 1: Initialize git, clean dependencies, add required libraries

**Goal:** Get the project onto a clean dependency footprint (AppCompat + Material + CardView added; Compose and unused Lifecycle/WorkManager removed) and under version control for per-task commits.

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1.1: Initialize git repository**

Run from project root:
```bash
git init
git add .
git commit -m "chore: initial commit — existing project state before network-monitor rework"
```

Expected: `[main (root-commit) <hash>] chore: ...` with ~40 files added.

- [ ] **Step 1.2: Rewrite `app/build.gradle.kts`**

Replace the entire file contents with:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.codingninjas.networkmonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codingninjas.networkmonitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(project.findProperty("KEYSTORE_PATH") as String? ?: "unset")
            storePassword = project.findProperty("KEYSTORE_PASSWORD") as String? ?: ""
            keyAlias = project.findProperty("KEY_ALIAS") as String? ?: ""
            keyPassword = project.findProperty("KEY_PASSWORD") as String? ?: ""
        }
    }

    buildTypes {
        release {
            // Intentionally disabled for internal side-loaded builds.
            // Preserves full class/method names in adb logcat and in Gson/OkHttp reflection.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)

    // UI stack (View-based, not Compose)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Tests (JVM)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // org.json is only stubs in the Android JVM — tests need the real impl
    testImplementation("org.json:json:20231013")

    // Instrumented tests (reserved for future Espresso work)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

**Note on `compileSdk = 36` (not the block form):** Reverting from the template's non-standard `compileSdk { version = release(36) { minorApiLevel = 1 } }` DSL — that form isn't documented in AGP and risks a sync failure. Standard scalar assignment works across all AGP 8.x / 9.x variants. If the project depends on any non-standard AGP behavior (which is unlikely for an internal app), revert the change and deal with the sync error case-by-case.

**Note on AGP/Kotlin version pins:** Before running Step 1.4, verify that `agp = "9.1.1"` and `kotlin = "2.2.10"` are real published versions by checking https://maven.google.com/web/index.html and https://kotlinlang.org/docs/releases.html respectively. If either is fictional, substitute the latest stable release.

- [ ] **Step 1.3: Update `gradle/libs.versions.toml`**

Replace the entire file with:

```toml
[versions]
agp = "9.1.1"
coreKtx = "1.10.1"
junit = "4.13.2"
junitVersion = "1.1.5"
espressoCore = "3.5.1"
kotlin = "2.2.10"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

- [ ] **Step 1.4: Sync Gradle and build to verify deps resolve**

Run: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | head -40`
Expected: Output contains `androidx.appcompat:appcompat:1.7.0`, `com.google.android.material:material:1.12.0`, `androidx.cardview:cardview:1.0.0`, `com.squareup.okhttp3:okhttp:4.12.0`, `com.google.code.gson:gson:2.10.1`. No references to `androidx.compose.*`.

- [ ] **Step 1.5: Commit**

```bash
git add app/build.gradle.kts gradle/libs.versions.toml
git commit -m "build: replace Compose stack with AppCompat + Material; add keystore signing config"
```

---

## Task 2: Delete dead Compose files

**Goal:** Remove orphaned Compose scaffold so the project compiles against the AppCompat stack.

**Files:**
- Delete: `app/src/main/java/com/codingninjas/networkmonitor/MainActivity.kt` (root-package Compose version)
- Delete: `app/src/main/java/com/codingninjas/networkmonitor/ui/theme/Color.kt`
- Delete: `app/src/main/java/com/codingninjas/networkmonitor/ui/theme/Theme.kt`
- Delete: `app/src/main/java/com/codingninjas/networkmonitor/ui/theme/Type.kt`
- Delete: `app/src/main/java/com/codingninjas/networkmonitor/PingWorker.kt`
- Delete: `app/src/main/java/com/codingninjas/networkmonitor/WorkScheduler.kt`

- [ ] **Step 2.1: Delete the six files**

```bash
rm app/src/main/java/com/codingninjas/networkmonitor/MainActivity.kt
rm -rf app/src/main/java/com/codingninjas/networkmonitor/ui/theme
rm app/src/main/java/com/codingninjas/networkmonitor/PingWorker.kt
rm app/src/main/java/com/codingninjas/networkmonitor/WorkScheduler.kt
```

(`rm -rf` on the `theme` directory handles `.DS_Store` and any hidden files that `rmdir` would refuse.)

- [ ] **Step 2.2: Verify deletion**

Run: `ls app/src/main/java/com/codingninjas/networkmonitor/`
Expected output (order may vary):
```
Constants.kt
MetricsCalculator.kt
NetworkUtils.kt
OnboardingActivity.kt
PingEngine.kt
SheetsUploader.kt
ui
```

Run: `ls app/src/main/java/com/codingninjas/networkmonitor/ui/`
Expected: `MainActivity.kt` only.

- [ ] **Step 2.3: Commit**

```bash
git add -A
git commit -m "chore: delete orphaned Compose scaffold + WorkManager files"
```

---

## Task 3: Fix theme and strings

**Goal:** Unblock `AppCompatActivity` by using a Material Components theme parent; add any required string resources.

**Files:**
- Modify: `app/src/main/res/values/themes.xml`

- [ ] **Step 3.1: Replace `themes.xml`**

Overwrite `app/src/main/res/values/themes.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.NetworkMonitorCN" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="colorPrimary">#4F46E5</item>
        <item name="android:windowBackground">#F5F5F5</item>
    </style>
</resources>
```

- [ ] **Step 3.2: Verify by grep**

Run: `grep parent app/src/main/res/values/themes.xml`
Expected: `    <style name="Theme.NetworkMonitorCN" parent="Theme.MaterialComponents.DayNight.NoActionBar">`

- [ ] **Step 3.3: Commit**

```bash
git add app/src/main/res/values/themes.xml
git commit -m "fix: use Theme.MaterialComponents parent for AppCompatActivity compatibility"
```

---

## Task 4: Rewrite Constants.kt

**Goal:** Define every constant the refactored codebase needs — timing, keys, HTTP, notification, email policy.

**Files:**
- Modify: `app/src/main/java/com/codingninjas/networkmonitor/Constants.kt`

- [ ] **Step 4.1: Overwrite `Constants.kt`**

Replace the full file contents with:

```kotlin
package com.codingninjas.networkmonitor

object Constants {
    // Webhook (unchanged from existing deployment)
    const val WEBHOOK_URL = "https://script.google.com/a/macros/codingninjas.com/s/AKfycbwc1kkq2KT2lLNrymksf289SozEGh8jI-_sVhx9xhHMZWTUpDwLAI90uRFHmWEN2gfXMQ/exec"

    // Ping target
    const val PING_TARGET = "8.8.8.8"
    const val PING_TIMEOUT_SEC = 2

    // Sampling + flush cadence
    const val SAMPLE_INTERVAL_MS = 1000L
    const val FLUSH_INTERVAL_MINUTES = 15L
    const val MAX_BUFFER_SAMPLES = 5400   // 90 minutes of samples, drop-oldest on overflow

    // HTTP timeouts (OkHttp)
    const val HTTP_CONNECT_TIMEOUT_SEC = 10L
    const val HTTP_WRITE_TIMEOUT_SEC = 10L
    const val HTTP_READ_TIMEOUT_SEC = 15L

    // Notification
    const val NOTIF_CHANNEL_ID = "nm_channel"
    const val NOTIF_CHANNEL_NAME = "Network Monitor"
    const val NOTIF_ID = 1001

    // Email policy
    const val ALLOWED_EMAIL_DOMAIN = "@codingninjas.com"

    // SharedPreferences
    const val PREFS_NAME = "nm_prefs"
    const val PREF_USER_ID = "user_id"
    const val PREF_LAST_RESULT = "last_result"
    const val PREF_LAST_UPDATE_TIME = "last_update_time"
    const val PREF_LAST_NETWORK_TYPE = "last_network_type"

    // Metadata
    const val APP_VERSION = "1.0"
}
```

- [ ] **Step 4.2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. If failures reference `MetricsCalculator`, `SheetsUploader`, or `OnboardingActivity` — those will be rewritten in subsequent tasks. Ignore for now.

If the build fails, **do not proceed**. Investigate before committing.

- [ ] **Step 4.3: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/Constants.kt
git commit -m "refactor(Constants): rewrite for 1 Hz sampler + wall-clock flusher architecture"
```

---

## Task 5: Create Sample + SampleBuffer with unit tests (TDD)

**Goal:** Thread-safe sample accumulator backed by an `ArrayDeque`, with add/drain/prepend semantics and bounded size. This is pure logic — ideal for TDD.

**Files:**
- Create: `app/src/main/java/com/codingninjas/networkmonitor/SampleBuffer.kt`
- Create: `app/src/test/java/com/codingninjas/networkmonitor/SampleBufferTest.kt`

- [ ] **Step 5.1: Write failing tests first**

Create `app/src/test/java/com/codingninjas/networkmonitor/SampleBufferTest.kt`:

```kotlin
package com.codingninjas.networkmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleBufferTest {

    @Test
    fun `drain returns empty list when buffer is empty`() {
        val buffer = SampleBuffer()
        assertEquals(emptyList<Sample>(), buffer.drain())
    }

    @Test
    fun `add then drain returns inserted samples in insertion order`() {
        val buffer = SampleBuffer()
        buffer.add(Sample(rttMs = 10.0, tsMs = 1_000L))
        buffer.add(Sample(rttMs = 20.0, tsMs = 2_000L))
        val drained = buffer.drain()
        assertEquals(2, drained.size)
        assertEquals(10.0, drained[0].rttMs)
        assertEquals(20.0, drained[1].rttMs)
    }

    @Test
    fun `drain clears the buffer`() {
        val buffer = SampleBuffer()
        buffer.add(Sample(rttMs = 10.0, tsMs = 1_000L))
        buffer.drain()
        assertEquals(emptyList<Sample>(), buffer.drain())
    }

    @Test
    fun `prepend puts retained samples before existing samples`() {
        val buffer = SampleBuffer()
        buffer.add(Sample(rttMs = 30.0, tsMs = 3_000L))
        buffer.prepend(listOf(
            Sample(rttMs = 10.0, tsMs = 1_000L),
            Sample(rttMs = 20.0, tsMs = 2_000L)
        ))
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(10.0, drained[0].rttMs)
        assertEquals(20.0, drained[1].rttMs)
        assertEquals(30.0, drained[2].rttMs)
    }

    @Test
    fun `add beyond capacity evicts oldest samples`() {
        val buffer = SampleBuffer(maxSize = 3)
        buffer.add(Sample(rttMs = 1.0, tsMs = 1L))
        buffer.add(Sample(rttMs = 2.0, tsMs = 2L))
        buffer.add(Sample(rttMs = 3.0, tsMs = 3L))
        buffer.add(Sample(rttMs = 4.0, tsMs = 4L))   // evicts rttMs=1.0
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(listOf(2.0, 3.0, 4.0), drained.map { it.rttMs })
    }

    @Test
    fun `prepend beyond capacity evicts oldest combined samples`() {
        val buffer = SampleBuffer(maxSize = 3)
        buffer.add(Sample(rttMs = 3.0, tsMs = 3L))
        buffer.add(Sample(rttMs = 4.0, tsMs = 4L))
        // prepend 3 retained → combined 5 → evict 2 oldest, keep last 3: [2.0, 3.0, 4.0]
        buffer.prepend(listOf(
            Sample(rttMs = 0.0, tsMs = 0L),
            Sample(rttMs = 1.0, tsMs = 1L),
            Sample(rttMs = 2.0, tsMs = 2L)
        ))
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(listOf(2.0, 3.0, 4.0), drained.map { it.rttMs })
    }

    @Test
    fun `null rttMs samples are preserved`() {
        val buffer = SampleBuffer()
        buffer.add(Sample(rttMs = null, tsMs = 1L))
        buffer.add(Sample(rttMs = 15.5, tsMs = 2L))
        buffer.add(Sample(rttMs = null, tsMs = 3L))
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(null, drained[0].rttMs)
        assertEquals(15.5, drained[1].rttMs)
        assertEquals(null, drained[2].rttMs)
    }

    @Test
    fun `concurrent add and drain do not corrupt state`() {
        val buffer = SampleBuffer(maxSize = 10_000)
        val threads = (0..9).map { threadId ->
            Thread {
                repeat(1000) { i ->
                    buffer.add(Sample(rttMs = threadId.toDouble(), tsMs = i.toLong()))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val drained = buffer.drain()
        assertEquals(10_000, drained.size)   // no lost adds, no duplicates
        assertTrue(drained.all { it.rttMs != null })
    }
}
```

- [ ] **Step 5.2: Run tests to confirm they fail (class not yet defined)**

Run: `./gradlew :app:testDebugUnitTest --tests com.codingninjas.networkmonitor.SampleBufferTest`
Expected: Compilation failure — `Unresolved reference: SampleBuffer` / `Unresolved reference: Sample`.

- [ ] **Step 5.3: Create `SampleBuffer.kt` with `Sample` and `SampleBuffer`**

Create `app/src/main/java/com/codingninjas/networkmonitor/SampleBuffer.kt`:

```kotlin
package com.codingninjas.networkmonitor

data class Sample(
    val rttMs: Double?,
    val tsMs: Long
)

class SampleBuffer(private val maxSize: Int = Constants.MAX_BUFFER_SAMPLES) {

    private val samples = ArrayDeque<Sample>()
    private val lock = Any()

    fun add(sample: Sample) = synchronized(lock) {
        samples.addLast(sample)
        while (samples.size > maxSize) samples.removeFirst()
    }

    fun drain(): List<Sample> = synchronized(lock) {
        val out = samples.toList()
        samples.clear()
        out
    }

    fun prepend(retained: List<Sample>) = synchronized(lock) {
        val merged = ArrayDeque<Sample>(retained.size + samples.size)
        merged.addAll(retained)
        merged.addAll(samples)
        while (merged.size > maxSize) merged.removeFirst()
        samples.clear()
        samples.addAll(merged)
    }
}
```

- [ ] **Step 5.4: Run tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.codingninjas.networkmonitor.SampleBufferTest`
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 5.5: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/SampleBuffer.kt \
        app/src/test/java/com/codingninjas/networkmonitor/SampleBufferTest.kt
git commit -m "feat(SampleBuffer): thread-safe sample accumulator with bounded capacity"
```

---

## Task 6: Rewrite MetricsCalculator with unit tests (TDD)

**Goal:** Replace the old `calculate(PingResult)` with `aggregate(samples: List<Sample>): NetworkMetrics` producing rich aggregate metrics (avg/min/max/p50/p95/p99/jitter/loss/samples_count/max_rtt_offset_sec) with nullable fields on total-loss windows.

**Files:**
- Modify: `app/src/main/java/com/codingninjas/networkmonitor/MetricsCalculator.kt`
- Create: `app/src/test/java/com/codingninjas/networkmonitor/MetricsCalculatorTest.kt`

- [ ] **Step 6.1: Write failing tests first**

Create `app/src/test/java/com/codingninjas/networkmonitor/MetricsCalculatorTest.kt`:

```kotlin
package com.codingninjas.networkmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsCalculatorTest {

    @Test
    fun `all successful samples produce rounded aggregates`() {
        val baseTs = 1_000_000_000L
        val rtts = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val samples = rtts.mapIndexed { i, rtt -> Sample(rtt, baseTs + i * 1000L) }
        val m = MetricsCalculator.aggregate(samples)

        assertEquals(30.0, m.avgPing)
        assertEquals(10.0, m.minPing)
        assertEquals(50.0, m.maxPing)
        assertEquals(30.0, m.p50Ping)          // median
        assertEquals(48.0, m.p95Ping!!, 0.001) // linear interpolation: 50*0.8 + 40*0.2? No — (0.95 * 4 = 3.8) → sorted[3]=40 + 0.8*(50-40) = 48
        assertEquals(49.6, m.p99Ping!!, 0.01)  // 0.99 * 4 = 3.96 → sorted[3]=40 + 0.96*(50-40) = 49.6
        assertEquals(0.0, m.packetLoss)
        assertEquals(5, m.samplesCount)
        assertEquals(4, m.maxRttOffsetSec)     // last sample (50.0) at offset 4
    }

    @Test
    fun `jitter is population stddev`() {
        // Samples with known stddev: [2, 4, 4, 4, 5, 5, 7, 9] → mean=5, stddev=2.0
        val samples = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
            .mapIndexed { i, rtt -> Sample(rtt, 1_000_000_000L + i * 1000L) }
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(2.0, m.jitter!!, 0.001)
    }

    @Test
    fun `single sample window`() {
        val m = MetricsCalculator.aggregate(listOf(Sample(42.0, 1_000L)))
        assertEquals(42.0, m.avgPing)
        assertEquals(42.0, m.minPing)
        assertEquals(42.0, m.maxPing)
        assertEquals(42.0, m.p50Ping)
        assertEquals(42.0, m.p99Ping)
        assertEquals(0.0, m.jitter)
        assertEquals(0.0, m.packetLoss)
        assertEquals(1, m.samplesCount)
        assertEquals(0, m.maxRttOffsetSec)
    }

    @Test
    fun `partial loss — half samples failed`() {
        val base = 1_000_000L
        val samples = listOf(
            Sample(10.0, base),
            Sample(null, base + 1000),
            Sample(20.0, base + 2000),
            Sample(null, base + 3000)
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(50.0, m.packetLoss)
        assertEquals(15.0, m.avgPing)
        assertEquals(4, m.samplesCount)
    }

    @Test
    fun `total loss window — all RTT fields are null, loss is 100, samples_count preserved`() {
        val base = 1_000_000L
        val samples = (0..9).map { Sample(null, base + it * 1000L) }
        val m = MetricsCalculator.aggregate(samples)
        assertNull(m.avgPing)
        assertNull(m.minPing)
        assertNull(m.maxPing)
        assertNull(m.p50Ping)
        assertNull(m.p95Ping)
        assertNull(m.p99Ping)
        assertNull(m.jitter)
        assertNull(m.maxRttOffsetSec)
        assertEquals(100.0, m.packetLoss)
        assertEquals(10, m.samplesCount)
    }

    @Test
    fun `empty list — all fields null or zero, loss is zero by convention`() {
        val m = MetricsCalculator.aggregate(emptyList())
        assertNull(m.avgPing)
        assertEquals(0.0, m.packetLoss)   // no samples → no attempt → no loss either
        assertEquals(0, m.samplesCount)
    }

    @Test
    fun `maxRttOffsetSec is relative to earliest sample in drain`() {
        val base = 1_000_000_000L
        // offsets:   0,    10,   20,   30
        // rtts:      10,   20,   100,  15      → worst at offset 20
        val samples = listOf(
            Sample(10.0,  base),
            Sample(20.0,  base + 10_000L),
            Sample(100.0, base + 20_000L),
            Sample(15.0,  base + 30_000L)
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(100.0, m.maxPing)
        assertEquals(20, m.maxRttOffsetSec)
    }

    @Test
    fun `maxRttOffsetSec ignores null samples when finding max`() {
        val base = 500L
        val samples = listOf(
            Sample(null, base),          // offset 0, excluded
            Sample(50.0, base + 1000),   // offset 1
            Sample(null, base + 2000),   // offset 2, excluded
            Sample(99.0, base + 3000)    // offset 3, WINS
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(99.0, m.maxPing)
        assertEquals(3, m.maxRttOffsetSec)
    }

    @Test
    fun `rounding is to one decimal place`() {
        val samples = (1..3).map { Sample(it * 0.333333, 1_000L + it * 1000L) }
        val m = MetricsCalculator.aggregate(samples)
        // mean = 0.666666 → 0.7
        assertEquals(0.7, m.avgPing!!, 0.0001)
    }

    @Test
    fun `ties on max RTT resolve to earliest occurrence`() {
        // Two samples share the maximum value of 99.0.
        // Policy: earliest timestamp wins → offset should reflect the first one.
        val base = 10_000L
        val samples = listOf(
            Sample(50.0, base),
            Sample(99.0, base + 5_000L),    // offset 5 — should win
            Sample(30.0, base + 10_000L),
            Sample(99.0, base + 15_000L)    // offset 15 — tie, but not first
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(99.0, m.maxPing)
        assertEquals(5, m.maxRttOffsetSec)
    }

    @Test
    fun `empty buffer drain feeds aggregate without crash and produces zero-loss null-fields`() {
        // Integration-level assertion for the PingService flusher contract:
        // drain can return empty, and aggregate must tolerate it without throwing.
        val buffer = SampleBuffer()
        val drained = buffer.drain()
        val m = MetricsCalculator.aggregate(drained)
        assertEquals(0, m.samplesCount)
        assertEquals(0.0, m.packetLoss, 0.0)  // no attempts → no loss
        assertNull(m.avgPing)
        assertNull(m.maxRttOffsetSec)
    }
}
```

- [ ] **Step 6.2: Run tests to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.codingninjas.networkmonitor.MetricsCalculatorTest`
Expected: Compilation failure — `aggregate()` doesn't exist, `NetworkMetrics` fields are non-null.

- [ ] **Step 6.3: Rewrite `MetricsCalculator.kt`**

Replace the full contents of `app/src/main/java/com/codingninjas/networkmonitor/MetricsCalculator.kt`:

```kotlin
package com.codingninjas.networkmonitor

import kotlin.math.pow
import kotlin.math.sqrt

data class NetworkMetrics(
    val avgPing: Double?,
    val minPing: Double?,
    val maxPing: Double?,
    val p50Ping: Double?,
    val p95Ping: Double?,
    val p99Ping: Double?,
    val jitter: Double?,
    val packetLoss: Double,
    val samplesCount: Int,
    val maxRttOffsetSec: Int?
)

object MetricsCalculator {

    fun aggregate(samples: List<Sample>): NetworkMetrics {
        if (samples.isEmpty()) {
            return NetworkMetrics(
                avgPing = null, minPing = null, maxPing = null,
                p50Ping = null, p95Ping = null, p99Ping = null,
                jitter = null, packetLoss = 0.0, samplesCount = 0,
                maxRttOffsetSec = null
            )
        }

        val total = samples.size
        val successful = samples.filter { it.rttMs != null }
        val rtts = successful.mapNotNull { it.rttMs }
        val loss = ((total - rtts.size).toDouble() / total) * 100

        if (rtts.isEmpty()) {
            return NetworkMetrics(
                avgPing = null, minPing = null, maxPing = null,
                p50Ping = null, p95Ping = null, p99Ping = null,
                jitter = null, packetLoss = round1(loss), samplesCount = total,
                maxRttOffsetSec = null
            )
        }

        val windowStartMs = samples.minOf { it.tsMs }
        val sorted = rtts.sorted()
        val mean = rtts.sum() / rtts.size
        val variance = rtts.sumOf { (it - mean).pow(2) } / rtts.size
        val stdDev = sqrt(variance)

        val maxRtt = rtts.max()
        val maxSample = successful.first { it.rttMs == maxRtt }
        val maxOffset = ((maxSample.tsMs - windowStartMs) / 1000L).toInt()

        return NetworkMetrics(
            avgPing = round1(mean),
            minPing = round1(sorted.first()),
            maxPing = round1(sorted.last()),
            p50Ping = round1(percentile(sorted, 50.0)),
            p95Ping = round1(percentile(sorted, 95.0)),
            p99Ping = round1(percentile(sorted, 99.0)),
            jitter = round1(stdDev),
            packetLoss = round1(loss),
            samplesCount = total,
            maxRttOffsetSec = maxOffset
        )
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        // Caller (aggregate) must filter empty RTT lists before reaching this.
        // Total-loss windows are routed to null fields without invoking percentile().
        require(sorted.isNotEmpty()) { "percentile() called on empty list — caller bug" }
        if (sorted.size == 1) return sorted[0]
        val rank = (p / 100.0) * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = rank - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
```

- [ ] **Step 6.4: Run tests to confirm they all pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.codingninjas.networkmonitor.MetricsCalculatorTest`
Expected: `BUILD SUCCESSFUL`, 11 tests passed.

- [ ] **Step 6.5: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/MetricsCalculator.kt \
        app/src/test/java/com/codingninjas/networkmonitor/MetricsCalculatorTest.kt
git commit -m "refactor(MetricsCalculator): rich aggregate over sample windows with nullable fields for total-loss"
```

---

## Task 7: Rewrite SheetsUploader + SheetPayload with MockWebServer tests

**Goal:** Schema-matching payload (15 fields, nullable RTTs), dual-gate success check (HTTP status + body JSON), configured OkHttp client with explicit timeouts.

**Files:**
- Modify: `app/src/main/java/com/codingninjas/networkmonitor/SheetsUploader.kt`
- Create: `app/src/test/java/com/codingninjas/networkmonitor/SheetsUploaderTest.kt`

- [ ] **Step 7.1: Write failing tests first**

Create `app/src/test/java/com/codingninjas/networkmonitor/SheetsUploaderTest.kt`:

```kotlin
package com.codingninjas.networkmonitor

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SheetsUploaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fullPayload() = SheetPayload(
        windowStart = "2026-04-16T12:00:00Z",
        userId = "chinmay.ramraika@codingninjas.com",
        deviceModel = "Samsung SM-G998B",
        networkType = "WiFi",
        avgPingMs = 42.3,
        minPingMs = 18.1,
        maxPingMs = 812.0,
        p50PingMs = 38.0,
        p95PingMs = 74.5,
        p99PingMs = 340.0,
        jitterMs = 28.7,
        packetLossPct = 3.3,
        samplesCount = 900,
        maxRttOffsetSec = 342,
        appVersion = "1.0"
    )

    private fun nullPayload() = fullPayload().copy(
        avgPingMs = null, minPingMs = null, maxPingMs = null,
        p50PingMs = null, p95PingMs = null, p99PingMs = null,
        jitterMs = null, maxRttOffsetSec = null,
        packetLossPct = 100.0
    )

    @Test
    fun `200 with status ok returns true`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"status":"ok"}""")
            .setHeader("Content-Type", "application/json"))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertTrue(uploader.upload(fullPayload()))
    }

    @Test
    fun `200 with status error returns false`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"status":"error","message":"whatever"}""")
            .setHeader("Content-Type", "application/json"))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `200 with HTML body returns false`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("<html><body>Error occurred</body></html>")
            .setHeader("Content-Type", "text/html"))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `500 returns false even with status ok body`() {
        server.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `connection failure returns false`() {
        server.shutdown()   // close before call
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `payload serializes all 15 fields with correct JSON keys`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString())
        uploader.upload(fullPayload())
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)

        assertEquals("2026-04-16T12:00:00Z", json.getString("window_start"))
        assertEquals("chinmay.ramraika@codingninjas.com", json.getString("user_id"))
        assertEquals("Samsung SM-G998B", json.getString("device_model"))
        assertEquals("WiFi", json.getString("network_type"))
        assertEquals(42.3, json.getDouble("avg_ping_ms"), 0.001)
        assertEquals(18.1, json.getDouble("min_ping_ms"), 0.001)
        assertEquals(812.0, json.getDouble("max_ping_ms"), 0.001)
        assertEquals(38.0, json.getDouble("p50_ping_ms"), 0.001)
        assertEquals(74.5, json.getDouble("p95_ping_ms"), 0.001)
        assertEquals(340.0, json.getDouble("p99_ping_ms"), 0.001)
        assertEquals(28.7, json.getDouble("jitter_ms"), 0.001)
        assertEquals(3.3, json.getDouble("packet_loss_pct"), 0.001)
        assertEquals(900, json.getInt("samples_count"))
        assertEquals(342, json.getInt("max_rtt_offset_sec"))
        assertEquals("1.0", json.getString("app_version"))
    }

    @Test
    fun `null RTT fields are serialized as JSON null`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString())
        uploader.upload(nullPayload())
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)

        assertTrue("avg should be null", json.isNull("avg_ping_ms"))
        assertTrue("min should be null", json.isNull("min_ping_ms"))
        assertTrue("max should be null", json.isNull("max_ping_ms"))
        assertTrue("p50 should be null", json.isNull("p50_ping_ms"))
        assertTrue("p95 should be null", json.isNull("p95_ping_ms"))
        assertTrue("p99 should be null", json.isNull("p99_ping_ms"))
        assertTrue("jitter should be null", json.isNull("jitter_ms"))
        assertTrue("max_rtt_offset_sec should be null", json.isNull("max_rtt_offset_sec"))
        assertEquals(100.0, json.getDouble("packet_loss_pct"), 0.001)
        assertEquals(900, json.getInt("samples_count"))
    }
}
```

- [ ] **Step 7.2: Run tests to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.codingninjas.networkmonitor.SheetsUploaderTest`
Expected: Compilation failure — `SheetPayload` has wrong fields, `SheetsUploader` constructor doesn't accept URL.

- [ ] **Step 7.3: Rewrite `SheetsUploader.kt`**

Replace the full contents:

```kotlin
package com.codingninjas.networkmonitor

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SheetPayload(
    @SerializedName("window_start") val windowStart: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_model") val deviceModel: String,
    @SerializedName("network_type") val networkType: String,
    @SerializedName("avg_ping_ms") val avgPingMs: Double?,
    @SerializedName("min_ping_ms") val minPingMs: Double?,
    @SerializedName("max_ping_ms") val maxPingMs: Double?,
    @SerializedName("p50_ping_ms") val p50PingMs: Double?,
    @SerializedName("p95_ping_ms") val p95PingMs: Double?,
    @SerializedName("p99_ping_ms") val p99PingMs: Double?,
    @SerializedName("jitter_ms") val jitterMs: Double?,
    @SerializedName("packet_loss_pct") val packetLossPct: Double,
    @SerializedName("samples_count") val samplesCount: Int,
    @SerializedName("max_rtt_offset_sec") val maxRttOffsetSec: Int?,
    @SerializedName("app_version") val appVersion: String
)

class SheetsUploader(private val webhookUrl: String = Constants.WEBHOOK_URL) {

    private val tag = "NM.Upload"
    private val jsonMedia = "application/json".toMediaType()
    private val gson = GsonBuilder().serializeNulls().create()
    private val client = OkHttpClient.Builder()
        .connectTimeout(Constants.HTTP_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(Constants.HTTP_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Constants.HTTP_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    fun upload(payload: SheetPayload): Boolean {
        return try {
            val body = gson.toJson(payload).toRequestBody(jsonMedia)
            val request = Request.Builder().url(webhookUrl).post(body).build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val httpOk = response.isSuccessful
                val bodyOk = try {
                    JSONObject(bodyStr).optString("status") == "ok"
                } catch (_: Exception) {
                    false
                }
                val ok = httpOk && bodyOk
                if (ok) {
                    Log.i(tag, "upload ok (status=${response.code}, samples=${payload.samplesCount})")
                } else {
                    Log.w(tag, "upload failed (status=${response.code}, httpOk=$httpOk, bodyOk=$bodyOk, body=${bodyStr.take(200)})")
                }
                ok
            }
        } catch (e: Exception) {
            Log.e(tag, "upload threw", e)
            false
        }
    }
}
```

- [ ] **Step 7.4: Run tests to confirm they all pass**

`testOptions { unitTests.isReturnDefaultValues = true }` and `testImplementation("org.json:json:...")` were both added in Task 1, so the JVM test environment already handles `android.util.Log` stubs and real `org.json.JSONObject` parsing.

Run: `./gradlew :app:testDebugUnitTest --tests com.codingninjas.networkmonitor.SheetsUploaderTest`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 7.5: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/SheetsUploader.kt \
        app/src/test/java/com/codingninjas/networkmonitor/SheetsUploaderTest.kt
git commit -m "refactor(SheetsUploader): 15-field payload + dual-gate success check + unit tests"
```

---

## Task 8: NotificationHelper

**Goal:** Centralize notification channel creation and the static ongoing notification used by `PingService`.

**Files:**
- Create: `app/src/main/java/com/codingninjas/networkmonitor/NotificationHelper.kt`

- [ ] **Step 8.1: Create `NotificationHelper.kt`**

```kotlin
package com.codingninjas.networkmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.codingninjas.networkmonitor.ui.MainActivity

object NotificationHelper {

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(Constants.NOTIF_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            Constants.NOTIF_CHANNEL_ID,
            Constants.NOTIF_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while network monitoring is running."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun buildOngoing(context: Context): android.app.Notification {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, Constants.NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Network Monitor")
            .setContentText("Tap to open")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }
}
```

**Deliberate spec deviation:** spec §6.2 says the notification text is the single string `"Network Monitor — tap to open"`. This implementation uses `setContentTitle("Network Monitor")` + `setContentText("Tap to open")` — Android's standard two-line format (bold title + subtitle). The rendered result is better than a single concatenated line and matches system notification conventions. Verification in §11 should pass despite the textual difference.

- [ ] **Step 8.2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (there may be warnings; no errors).

- [ ] **Step 8.3: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/NotificationHelper.kt
git commit -m "feat(NotificationHelper): channel + ongoing notification builder"
```

---

## Task 9: PingService scaffold (foreground + notification, no loops yet)

**Goal:** Create a startable foreground service with the persistent notification, but without sampling or flushing yet. This is a milestone: we can install the app and confirm the notification appears before risking the more complex coroutine logic.

**Files:**
- Create: `app/src/main/java/com/codingninjas/networkmonitor/service/PingService.kt`

- [ ] **Step 9.1: Create the scaffold**

```kotlin
package com.codingninjas.networkmonitor.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PingService : Service() {

    private val tag = "NM.Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopsStarted = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        val notification = NotificationHelper.buildOngoing(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Constants.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constants.NOTIF_ID, notification)
        }
        Log.i(tag, "service created and foregrounded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopsStarted) return START_STICKY
        loopsStarted = true
        // Loops will be started here in Task 10. Currently no-op.
        Log.i(tag, "onStartCommand — loops placeholder")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        Log.i(tag, "service destroyed")
        super.onDestroy()
    }
}
```

- [ ] **Step 9.2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9.3: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/service/PingService.kt
git commit -m "feat(PingService): foreground service scaffold with persistent notification"
```

---

## Task 10: PingService sampler + flusher loops

**Goal:** Add the two coroutines — 1 Hz sampler calling `PingEngine` and wall-clock-aligned flusher calling `MetricsCalculator` + `SheetsUploader`. Each loop wraps its body in try/catch so a transient exception doesn't kill the coroutine.

**Files:**
- Modify: `app/src/main/java/com/codingninjas/networkmonitor/service/PingService.kt`

- [ ] **Step 10.1: Rewrite `PingService.kt` with loops**

Replace the full contents:

```kotlin
package com.codingninjas.networkmonitor.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.MetricsCalculator
import com.codingninjas.networkmonitor.NetworkMetrics
import com.codingninjas.networkmonitor.NetworkUtils
import com.codingninjas.networkmonitor.NotificationHelper
import com.codingninjas.networkmonitor.PingEngine
import com.codingninjas.networkmonitor.Sample
import com.codingninjas.networkmonitor.SampleBuffer
import com.codingninjas.networkmonitor.SheetPayload
import com.codingninjas.networkmonitor.SheetsUploader
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

class PingService : Service() {

    private val tag = "NM.Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = SampleBuffer()
    private val uploader = SheetsUploader()
    // Matches SheetsUploader's serialization settings — null fields are preserved
    // so the prefs round-trip behaves identically to what the Sheet receives.
    private val gson = GsonBuilder().serializeNulls().create()
    private var loopsStarted = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        val notification = NotificationHelper.buildOngoing(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Constants.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constants.NOTIF_ID, notification)
        }
        Log.i(tag, "service created and foregrounded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopsStarted) return START_STICKY
        loopsStarted = true
        scope.launch { samplerLoop() }
        scope.launch { flusherLoop() }
        Log.i(tag, "loops launched")
        return START_STICKY
    }

    private suspend fun samplerLoop() {
        val samplerTag = "NM.Sampler"
        while (coroutineContext.isActive) {
            try {
                val t0 = System.currentTimeMillis()
                val result = PingEngine.runPing(
                    Constants.PING_TARGET,
                    count = 1,
                    timeoutSec = Constants.PING_TIMEOUT_SEC
                )
                val rtt = if (result.success) result.rtts.firstOrNull() else null
                buffer.add(Sample(rttMs = rtt, tsMs = t0))
                val elapsed = System.currentTimeMillis() - t0
                delay((Constants.SAMPLE_INTERVAL_MS - elapsed).coerceAtLeast(0))
            } catch (e: Exception) {
                Log.e(samplerTag, "sampler iteration threw", e)
                delay(Constants.SAMPLE_INTERVAL_MS)   // avoid tight error loop
            }
        }
    }

    private suspend fun flusherLoop() {
        val flusherTag = "NM.Flusher"
        val quarterMs = Constants.FLUSH_INTERVAL_MINUTES * 60_000L
        val initialDelay = quarterMs - (System.currentTimeMillis() % quarterMs)
        Log.i(flusherTag, "first flush in ${initialDelay / 1000}s")
        delay(initialDelay)
        while (coroutineContext.isActive) {
            try {
                runOneFlush()
            } catch (e: Exception) {
                Log.e(flusherTag, "flusher iteration threw", e)
            }
            delay(quarterMs)
        }
    }

    private fun runOneFlush() {
        val flusherTag = "NM.Flusher"
        val drained = buffer.drain()
        if (drained.isEmpty()) {
            Log.i(flusherTag, "drained 0 samples — skip")
            return
        }
        val metrics = MetricsCalculator.aggregate(drained)
        val networkType = NetworkUtils.getNetworkType(applicationContext)
        val windowStartMs = drained.minOf { it.tsMs }
        val payload = buildPayload(metrics, windowStartMs, networkType)

        val uploaded = uploader.upload(payload)
        if (uploaded) {
            persistToPrefs(metrics, networkType)
            Log.i(flusherTag, "flush ok — samples=${metrics.samplesCount} loss=${metrics.packetLoss}%")
        } else {
            buffer.prepend(drained)
            Log.w(flusherTag, "flush failed — retained ${drained.size} samples for next window")
        }
    }

    private fun buildPayload(
        metrics: NetworkMetrics,
        windowStartMs: Long,
        networkType: String
    ): SheetPayload {
        val userId = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_USER_ID, "") ?: ""
        return SheetPayload(
            windowStart = Instant.ofEpochMilli(windowStartMs).toString(),
            userId = userId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            networkType = networkType,
            avgPingMs = metrics.avgPing,
            minPingMs = metrics.minPing,
            maxPingMs = metrics.maxPing,
            p50PingMs = metrics.p50Ping,
            p95PingMs = metrics.p95Ping,
            p99PingMs = metrics.p99Ping,
            jitterMs = metrics.jitter,
            packetLossPct = metrics.packetLoss,
            samplesCount = metrics.samplesCount,
            maxRttOffsetSec = metrics.maxRttOffsetSec,
            appVersion = Constants.APP_VERSION
        )
    }

    private fun persistToPrefs(metrics: NetworkMetrics, networkType: String) {
        val displayTime = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_LAST_RESULT, gson.toJson(metrics))
            .putString(Constants.PREF_LAST_UPDATE_TIME, displayTime)
            .putString(Constants.PREF_LAST_NETWORK_TYPE, networkType)
            .apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        Log.i(tag, "service destroyed")
        super.onDestroy()
    }
}
```

- [ ] **Step 10.2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10.3: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/service/PingService.kt
git commit -m "feat(PingService): sampler + flusher loops with wall-clock alignment and retain-on-failure"
```

---

## Task 11: BootReceiver

**Goal:** Restart `PingService` after device reboot if the user has completed onboarding.

**Files:**
- Create: `app/src/main/java/com/codingninjas/networkmonitor/BootReceiver.kt`

- [ ] **Step 11.1: Create `BootReceiver.kt`**

```kotlin
package com.codingninjas.networkmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.codingninjas.networkmonitor.service.PingService

class BootReceiver : BroadcastReceiver() {

    private val tag = "NM.Boot"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString(Constants.PREF_USER_ID, "") ?: ""
        if (userId.isBlank()) {
            Log.i(tag, "boot received but no email saved — skipping service start")
            return
        }
        Log.i(tag, "boot received — starting PingService for $userId")
        ContextCompat.startForegroundService(
            context, Intent(context, PingService::class.java)
        )
    }
}
```

- [ ] **Step 11.2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11.3: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/BootReceiver.kt
git commit -m "feat(BootReceiver): restart PingService after device reboot if onboarded"
```

---

## Task 12: Update AndroidManifest.xml

**Goal:** Declare all required permissions, the service, and the receiver. All referenced classes now exist in code, so the manifest is safe to update.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 12.1: Overwrite `AndroidManifest.xml`**

Replace the full contents:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.NetworkMonitorCN">

        <!-- LAUNCHER: onboarding is the first screen.
             screenOrientation=portrait is defense-in-depth: prevents mid-onboarding
             config changes from losing pendingEmail / notifDenialCount state.
             (Activity also implements onSaveInstanceState as primary defense.) -->
        <activity
            android:name=".OnboardingActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.NetworkMonitorCN">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <!-- Dashboard — launched by OnboardingActivity or tapping the notification -->
        <activity
            android:name=".ui.MainActivity"
            android:exported="false"
            android:label="@string/app_name"
            android:theme="@style/Theme.NetworkMonitorCN"/>

        <!-- Foreground monitoring service -->
        <service
            android:name=".service.PingService"
            android:exported="false"
            android:foregroundServiceType="dataSync"/>

        <!-- Auto-start service after reboot -->
        <receiver
            android:name=".BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED"/>
            </intent-filter>
        </receiver>

    </application>

</manifest>
```

- [ ] **Step 12.2: Verify manifest by building debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. A warning about `RECEIVE_BOOT_COMPLETED` on API 26+ is acceptable. Do not proceed if there is a BUILD FAILED.

- [ ] **Step 12.3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(manifest): declare service, boot receiver, and foreground/notification permissions"
```

---

## Task 13: Rewrite OnboardingActivity (email + permissions + service launch)

**Goal:** Blocking email screen; validates `@codingninjas.com` format; three-step notification permission flow (grant → rationale → Settings redirect); battery-optimization exemption; starts service; opens MainActivity.

**Files:**
- Modify: `app/src/main/java/com/codingninjas/networkmonitor/OnboardingActivity.kt`
- Modify: `app/src/main/res/layout/activity_onboarding.xml`

- [ ] **Step 13.1: Rewrite layout `activity_onboarding.xml`**

Replace the full contents:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="32dp"
    android:background="#FFFFFF">

    <ImageView
        android:layout_width="80dp"
        android:layout_height="80dp"
        android:src="@android:drawable/ic_menu_compass"
        android:layout_gravity="center"
        android:layout_marginBottom="16dp"
        android:contentDescription="@null" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Network Monitor"
        android:textSize="28sp"
        android:textStyle="bold"
        android:textColor="#1A1A2E"
        android:gravity="center"
        android:layout_marginBottom="8dp" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="We\'ll sample network latency once per second and upload aggregate metrics every 15 minutes.\nEnter your work email so your data is labelled correctly."
        android:textSize="14sp"
        android:textColor="#666666"
        android:gravity="center"
        android:layout_marginBottom="32dp" />

    <EditText
        android:id="@+id/etEmail"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Your @codingninjas.com email"
        android:inputType="textEmailAddress"
        android:padding="12dp"
        android:layout_marginBottom="24dp"
        android:autofillHints="emailAddress" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnStart"
        android:layout_width="match_parent"
        android:layout_height="52dp"
        android:text="Start Monitoring"
        android:textColor="#FFFFFF"
        android:backgroundTint="#4F46E5"
        app:cornerRadius="8dp" />

</LinearLayout>
```

- [ ] **Step 13.2: Rewrite `OnboardingActivity.kt`**

Replace the full contents:

```kotlin
package com.codingninjas.networkmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.codingninjas.networkmonitor.service.PingService
import com.codingninjas.networkmonitor.ui.MainActivity

class OnboardingActivity : AppCompatActivity() {

    private val tag = "NM.Onboard"
    private var pendingEmail: String? = null
    private var notifDenialCount = 0
    private var awaitingSettingsReturn = false

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(tag, "notification permission granted")
            proceedAfterNotifGranted()
        } else {
            notifDenialCount++
            handleNotifDenial()
        }
    }

    // Activity Result launcher for the battery-exemption system dialog.
    // Proceeds to finalizeOnboarding regardless of whether user granted —
    // we've done our due diligence by asking.
    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        Log.i(tag, "battery-exemption dialog returned")
        finalizeOnboarding()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getString(Constants.PREF_USER_ID, "").isNullOrBlank()) {
            Log.i(tag, "already onboarded — routing to MainActivity")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Restore transient onboarding state across config change
        savedInstanceState?.let {
            pendingEmail = it.getString(KEY_PENDING_EMAIL)
            notifDenialCount = it.getInt(KEY_DENIAL_COUNT, 0)
            awaitingSettingsReturn = it.getBoolean(KEY_AWAITING_SETTINGS, false)
        }

        setContentView(R.layout.activity_onboarding)

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val btnStart = findViewById<Button>(R.id.btnStart)
        pendingEmail?.let { etEmail.setText(it) }

        btnStart.setOnClickListener {
            val email = etEmail.text.toString().trim()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!email.endsWith(Constants.ALLOWED_EMAIL_DOMAIN, ignoreCase = true)) {
                Toast.makeText(
                    this,
                    "Use your ${Constants.ALLOWED_EMAIL_DOMAIN} work email",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            pendingEmail = email
            checkNotifPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // If user went to app-notification Settings and returned, re-check the permission
        // automatically so they don't have to tap "Start Monitoring" again.
        if (awaitingSettingsReturn && pendingEmail != null) {
            awaitingSettingsReturn = false
            checkNotifPermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_EMAIL, pendingEmail)
        outState.putInt(KEY_DENIAL_COUNT, notifDenialCount)
        outState.putBoolean(KEY_AWAITING_SETTINGS, awaitingSettingsReturn)
    }

    private fun checkNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // API <33: permission granted at install time.
            proceedAfterNotifGranted()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            proceedAfterNotifGranted()
        } else {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleNotifDenial() {
        when (notifDenialCount) {
            1 -> {
                AlertDialog.Builder(this)
                    .setTitle("Notification required")
                    .setMessage("Monitoring needs this notification so Android lets it run in the background continuously.")
                    .setPositiveButton("Try again") { _, _ ->
                        requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .setCancelable(false)
                    .show()
            }
            else -> {
                AlertDialog.Builder(this)
                    .setTitle("Notification required")
                    .setMessage("Android won't show the prompt again. Open app notification settings to grant manually, then return to this app.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        awaitingSettingsReturn = true
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * Notification permission granted. Now ask about battery-optimization exemption.
     * We launch the system dialog via Activity Result so we can wait for the user's
     * response before proceeding to finalize — otherwise MainActivity launches on top
     * of the system dialog and the user never actually sees the battery prompt.
     */
    private fun proceedAfterNotifGranted() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(tag, "battery exemption already granted — skipping prompt")
            finalizeOnboarding()
            return
        }
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            batteryExemptionLauncher.launch(intent)
        } catch (e: Exception) {
            // Some OEM devices reject this intent. Log and proceed anyway.
            Log.w(tag, "failed to launch battery-exemption intent, proceeding", e)
            finalizeOnboarding()
        }
    }

    private fun finalizeOnboarding() {
        val email = pendingEmail ?: return
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_USER_ID, email)
            .apply()
        ContextCompat.startForegroundService(this, Intent(this, PingService::class.java))
        Log.i(tag, "onboarding done — email=$email, service started")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val KEY_PENDING_EMAIL = "pending_email"
        private const val KEY_DENIAL_COUNT = "notif_denial_count"
        private const val KEY_AWAITING_SETTINGS = "awaiting_settings_return"
    }
}
```

- [ ] **Step 13.3: Verify compilation + build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 13.4: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/OnboardingActivity.kt \
        app/src/main/res/layout/activity_onboarding.xml
git commit -m "feat(OnboardingActivity): email-gated with domain restriction and tiered permission flow"
```

---

## Task 14: Rewrite MainActivity + layout (P99 dashboard, persisted network)

**Goal:** Six-cell dashboard — AVG/MAX/P99/JITTER/LOSS/NETWORK — reading entirely from persisted prefs (no live queries).

**Files:**
- Modify: `app/src/main/java/com/codingninjas/networkmonitor/ui/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 14.1: Rewrite layout `activity_main.xml`**

Replace the full contents:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#F5F5F5"
    android:padding="20dp">

    <TextView
        android:id="@+id/tvGreeting"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Hi,"
        android:textSize="22sp"
        android:textStyle="bold"
        android:textColor="#1A1A2E"
        android:layout_marginBottom="4dp" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Background monitoring is active"
        android:textSize="12sp"
        android:textColor="#4F46E5"
        android:layout_marginBottom="20dp" />

    <androidx.cardview.widget.CardView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:cardCornerRadius="12dp"
        app:cardElevation="4dp"
        app:cardBackgroundColor="#FFFFFF"
        app:contentPadding="16dp">

        <GridLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:columnCount="2">

            <!-- AVG PING -->
            <LinearLayout
                android:orientation="vertical"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:layout_gravity="fill_horizontal"
                android:padding="8dp">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="AVG PING" android:textSize="11sp" android:textColor="#999999" />
                <TextView android:id="@+id/tvAvg" android:layout_width="wrap_content"
                          android:layout_height="wrap_content" android:text="—"
                          android:textSize="22sp" android:textStyle="bold" android:textColor="#1A1A2E" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="ms" android:textSize="10sp" android:textColor="#BBBBBB" />
            </LinearLayout>

            <!-- MAX -->
            <LinearLayout
                android:orientation="vertical"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:layout_gravity="fill_horizontal"
                android:padding="8dp">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="MAX" android:textSize="11sp" android:textColor="#999999" />
                <TextView android:id="@+id/tvMax" android:layout_width="wrap_content"
                          android:layout_height="wrap_content" android:text="—"
                          android:textSize="22sp" android:textStyle="bold" android:textColor="#1A1A2E" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="ms" android:textSize="10sp" android:textColor="#BBBBBB" />
            </LinearLayout>

            <!-- P99 -->
            <LinearLayout
                android:orientation="vertical"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:layout_gravity="fill_horizontal"
                android:padding="8dp">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="P99" android:textSize="11sp" android:textColor="#999999" />
                <TextView android:id="@+id/tvP99" android:layout_width="wrap_content"
                          android:layout_height="wrap_content" android:text="—"
                          android:textSize="22sp" android:textStyle="bold" android:textColor="#1A1A2E" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="ms" android:textSize="10sp" android:textColor="#BBBBBB" />
            </LinearLayout>

            <!-- JITTER -->
            <LinearLayout
                android:orientation="vertical"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:layout_gravity="fill_horizontal"
                android:padding="8dp">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="JITTER" android:textSize="11sp" android:textColor="#999999" />
                <TextView android:id="@+id/tvJitter" android:layout_width="wrap_content"
                          android:layout_height="wrap_content" android:text="—"
                          android:textSize="22sp" android:textStyle="bold" android:textColor="#1A1A2E" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="ms" android:textSize="10sp" android:textColor="#BBBBBB" />
            </LinearLayout>

            <!-- PACKET LOSS -->
            <LinearLayout
                android:orientation="vertical"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:layout_gravity="fill_horizontal"
                android:padding="8dp">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="PACKET LOSS" android:textSize="11sp" android:textColor="#999999" />
                <TextView android:id="@+id/tvLoss" android:layout_width="wrap_content"
                          android:layout_height="wrap_content" android:text="—"
                          android:textSize="22sp" android:textStyle="bold" android:textColor="#1A1A2E" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="%" android:textSize="10sp" android:textColor="#BBBBBB" />
            </LinearLayout>

            <!-- NETWORK -->
            <LinearLayout
                android:orientation="vertical"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_columnWeight="1"
                android:layout_gravity="fill_horizontal"
                android:padding="8dp">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="NETWORK" android:textSize="11sp" android:textColor="#999999" />
                <TextView android:id="@+id/tvNetwork" android:layout_width="wrap_content"
                          android:layout_height="wrap_content" android:text="—"
                          android:textSize="22sp" android:textStyle="bold" android:textColor="#1A1A2E" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                          android:text="" android:textSize="10sp" android:textColor="#BBBBBB" />
            </LinearLayout>

        </GridLayout>
    </androidx.cardview.widget.CardView>

    <TextView
        android:id="@+id/tvLastUpdate"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Last updated: —"
        android:textSize="11sp"
        android:textColor="#AAAAAA"
        android:layout_marginTop="12dp"
        android:gravity="center" />

    <TextView
        android:id="@+id/tvNoData"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="No data yet.\nFirst test runs within 15 minutes."
        android:textSize="14sp"
        android:textColor="#999999"
        android:layout_marginTop="40dp"
        android:gravity="center"
        android:visibility="gone" />

</LinearLayout>
```

- [ ] **Step 14.2: Rewrite `ui/MainActivity.kt`**

Replace the full contents:

```kotlin
package com.codingninjas.networkmonitor.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.NetworkMetrics
import com.codingninjas.networkmonitor.R
import com.google.gson.Gson

class MainActivity : AppCompatActivity() {

    private lateinit var tvGreeting: TextView
    private lateinit var tvAvg: TextView
    private lateinit var tvMax: TextView
    private lateinit var tvP99: TextView
    private lateinit var tvJitter: TextView
    private lateinit var tvLoss: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var tvNoData: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvGreeting = findViewById(R.id.tvGreeting)
        tvAvg = findViewById(R.id.tvAvg)
        tvMax = findViewById(R.id.tvMax)
        tvP99 = findViewById(R.id.tvP99)
        tvJitter = findViewById(R.id.tvJitter)
        tvLoss = findViewById(R.id.tvLoss)
        tvNetwork = findViewById(R.id.tvNetwork)
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        tvNoData = findViewById(R.id.tvNoData)

        val email = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_USER_ID, "") ?: ""
        tvGreeting.text = "Hi, $email"
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(Constants.PREF_LAST_RESULT, null)
        val lastUpdate = prefs.getString(Constants.PREF_LAST_UPDATE_TIME, null)
        val network = prefs.getString(Constants.PREF_LAST_NETWORK_TYPE, null)

        if (json != null && lastUpdate != null && network != null) {
            val m = try {
                Gson().fromJson(json, NetworkMetrics::class.java)
            } catch (_: Exception) {
                null
            }
            if (m != null) {
                tvAvg.text = m.avgPing?.toString() ?: "—"
                tvMax.text = m.maxPing?.toString() ?: "—"
                tvP99.text = m.p99Ping?.toString() ?: "—"
                tvJitter.text = m.jitter?.toString() ?: "—"
                tvLoss.text = m.packetLoss.toString()
                tvNetwork.text = network
                tvLastUpdate.text = "Last updated: $lastUpdate"
                tvNoData.visibility = View.GONE
                return
            }
        }
        renderEmpty()
    }

    private fun renderEmpty() {
        tvAvg.text = "—"
        tvMax.text = "—"
        tvP99.text = "—"
        tvJitter.text = "—"
        tvLoss.text = "—"
        tvNetwork.text = "—"
        tvLastUpdate.text = "Last updated: —"
        tvNoData.visibility = View.VISIBLE
    }
}
```

- [ ] **Step 14.3: Build debug APK to verify**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 14.4: Commit**

```bash
git add app/src/main/java/com/codingninjas/networkmonitor/ui/MainActivity.kt \
        app/src/main/res/layout/activity_main.xml
git commit -m "feat(MainActivity): 6-cell dashboard AVG/MAX/P99/JITTER/LOSS/NETWORK from persisted prefs"
```

---

## Task 15: Generate keystore and configure Gradle properties

**Goal:** Create a release keystore and wire it to the existing signing config in `build.gradle.kts` via `~/.gradle/gradle.properties`.

**Files:**
- Create: `~/keystores/networkmonitor-release.jks` (outside repo)
- Modify: `~/.gradle/gradle.properties`

- [ ] **Step 15.1: Generate the keystore (one-time)**

```bash
mkdir -p ~/keystores
keytool -genkey -v \
  -keystore ~/keystores/networkmonitor-release.jks \
  -alias networkmonitor \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "REPLACE_ME_STRONG_PASSWORD" \
  -keypass "REPLACE_ME_STRONG_PASSWORD" \
  -dname "CN=Chinmay Ramraika, OU=Engineering, O=Coding Ninjas, L=Gurgaon, ST=Haryana, C=IN"
```

Substitute `REPLACE_ME_STRONG_PASSWORD` with a strong password (record it in a password manager before running — you will need it to update the app later).

Expected: `[Storing ~/keystores/networkmonitor-release.jks]` and no errors. Verify with:

```bash
keytool -list -keystore ~/keystores/networkmonitor-release.jks -storepass "REPLACE_ME_STRONG_PASSWORD"
```

- [ ] **Step 15.2: Add signing properties to `~/.gradle/gradle.properties`**

Append (not overwrite) to `~/.gradle/gradle.properties`:

```properties
KEYSTORE_PATH=/Users/chinmayramraika/keystores/networkmonitor-release.jks
KEYSTORE_PASSWORD=REPLACE_ME_STRONG_PASSWORD
KEY_ALIAS=networkmonitor
KEY_PASSWORD=REPLACE_ME_STRONG_PASSWORD
```

Use the exact password from Step 15.1.

**Portability note:** `KEYSTORE_PATH` above is a macOS absolute path under `/Users/chinmayramraika/`. If you ever build from a different machine (CI, another developer, Linux/Windows), adjust this path. A CI-friendly alternative is to keep the keystore in the repo root (outside `.git`) — e.g., `./keystores/...` — and use a relative path, but take care that the keystore is never committed; add `/keystores/` to `.gitignore`.

- [ ] **Step 15.3: Build release APK to verify signing**

Run: `./gradlew :app:assembleRelease`
Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/release/app-release.apk`.

Verify signature:
```bash
$ANDROID_HOME/build-tools/*/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```
Expected: `Verifies`. (If `apksigner` not on PATH, use the path Android Studio prints on a release build.)

- [ ] **Step 15.4: No git commit** (keystore and properties are outside repo)

---

## Task 16: Deploy Apps Script webhook (manual)

**Goal:** Replace the existing Apps Script deployment with the schema-matching version from §9 of the spec, verify via `doGet`.

**Files:**
- None in repo (manual work in Apps Script editor)

- [ ] **Step 16.1: Open the Apps Script project**

Navigate to https://script.google.com and open the existing project bound to the monitoring Sheet.

- [ ] **Step 16.2: Replace `Code.gs` contents with the schema-matching version**

```javascript
const SHEET_NAME = "Sheet1";

const HEADERS = [
  "window_start", "user_id", "device_model", "network_type",
  "avg_ping_ms", "min_ping_ms", "max_ping_ms",
  "p50_ping_ms", "p95_ping_ms", "p99_ping_ms",
  "jitter_ms", "packet_loss_pct",
  "samples_count", "max_rtt_offset_sec", "app_version"
];

function doPost(e) {
  const data = JSON.parse(e.postData.contents);
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  if (sheet.getLastRow() === 0) sheet.appendRow(HEADERS);
  sheet.appendRow([
    data.window_start, data.user_id, data.device_model, data.network_type,
    data.avg_ping_ms, data.min_ping_ms, data.max_ping_ms,
    data.p50_ping_ms, data.p95_ping_ms, data.p99_ping_ms,
    data.jitter_ms, data.packet_loss_pct,
    data.samples_count, data.max_rtt_offset_sec, data.app_version
  ]);
  return ContentService.createTextOutput(JSON.stringify({status: "ok"}))
    .setMimeType(ContentService.MimeType.JSON);
}

function doGet(e) {
  return ContentService.createTextOutput("Network Monitor webhook is live.");
}
```

- [ ] **Step 16.3: Redeploy the existing Web App**

In Apps Script editor: **Deploy → Manage deployments → (pencil icon on existing deployment) → Version: "New version" → Deploy**.

The `/exec` URL stays identical. Verify:

```bash
curl -s "https://script.google.com/a/macros/codingninjas.com/s/AKfycbwc1kkq2KT2lLNrymksf289SozEGh8jI-_sVhx9xhHMZWTUpDwLAI90uRFHmWEN2gfXMQ/exec"
```
Expected: `Network Monitor webhook is live.` (possibly preceded by redirect hops — use `-L` if curl doesn't follow automatically).

- [ ] **Step 16.4: Smoke-test POST from curl**

```bash
curl -L -X POST \
  "https://script.google.com/a/macros/codingninjas.com/s/AKfycbwc1kkq2KT2lLNrymksf289SozEGh8jI-_sVhx9xhHMZWTUpDwLAI90uRFHmWEN2gfXMQ/exec" \
  -H "Content-Type: application/json" \
  -d '{"window_start":"2026-04-16T00:00:00Z","user_id":"test@codingninjas.com","device_model":"curl","network_type":"WiFi","avg_ping_ms":42.0,"min_ping_ms":18.0,"max_ping_ms":812.0,"p50_ping_ms":38.0,"p95_ping_ms":74.0,"p99_ping_ms":340.0,"jitter_ms":28.0,"packet_loss_pct":3.3,"samples_count":900,"max_rtt_offset_sec":342,"app_version":"1.0"}'
```
Expected: `{"status":"ok"}` AND a new row in your Sheet with the test data AND a header row if the Sheet was empty.

Delete the test row from the Sheet after confirming.

- [ ] **Step 16.5: No git commit** (no local files changed — Apps Script change is out-of-repo)

---

## Task 17: Install release APK on test device and verify end-to-end

**Goal:** Run the full §11 verification checklist from the spec on a physical Android device (API 33+ recommended to exercise the notification permission flow). Each check either passes or blocks shipping.

**Files:**
- None (on-device verification)

- [ ] **Step 17.1: Transfer APK to device**

Upload `app/build/outputs/apk/release/app-release.apk` to Google Drive, share with your test account, and download on the target device. (Or: `adb install app/build/outputs/apk/release/app-release.apk` if connected.)

On device: enable *Install unknown apps* for Drive (or whatever app downloaded the APK): **Settings → Apps → [that app] → Install unknown apps → Allow**.

Tap the APK → tap past the "unknown developer" warning → install.

- [ ] **Step 17.2: Verification checks (per §11 of spec)**

Mark each as PASS / FAIL:

1. [ ] Launch app — no crash (if fail: check logcat for `Theme.AppCompat` crash → Task 3 regression).
2. [ ] Onboarding email screen blocks until a valid `@codingninjas.com` email is entered.
3. [ ] `POST_NOTIFICATIONS` prompt appears on first valid email submit (API 33+).
4. [ ] Denying notification once triggers rationale dialog; second denial opens Settings dialog.
5. [ ] Granting notification → battery-optimization exemption dialog appears.
6. [ ] After granting both, app opens MainActivity showing "Hi, <email>" and "No data yet".
7. [ ] Persistent notification "Network Monitor — tap to open" visible in shade.
8. [ ] `adb shell dumpsys activity services | grep PingService` shows the service running.
9. [ ] Wait until the next `:00/:15/:30/:45` wall-clock boundary. Sheet receives a new row. Header row auto-inserted if Sheet was empty. Column D shows correct network type.
10. [ ] Re-open app → dashboard shows populated values for AVG/MAX/P99/JITTER/LOSS/NETWORK with correct "Last updated: …" time.
11. [ ] Turn airplane mode ON; next flush attempt logs retain-on-failure (`adb logcat | grep "NM\."`). Turn airplane mode OFF; subsequent flush uploads a merged window with `samples_count > 900`.
12. [ ] Swipe app from recents. Notification persists. Next flush still fires; Sheet row appears.
13. [ ] Reboot device. Within ~30 seconds, `adb shell dumpsys activity services | grep PingService` shows the service restarted by `BootReceiver`. Sheet receives the next scheduled-boundary row.
14. [ ] Total-loss scenario: block internet for the full 15-minute window (firewall the ping target via router or put device in a Faraday state). Sheet row at next flush has `packet_loss_pct = 100.0`, RTT columns E–K and N empty.

- [ ] **Step 17.3: Retain verification artifacts**

Save the logcat output from Step 17.2 (any interesting windows) and a screenshot of the Sheet showing rows from all test windows to `docs/superpowers/plans/2026-04-16-verification-artifacts/` (create this directory if needed — it's git-tracked for future reference).

- [ ] **Step 17.4: Commit verification artifacts**

```bash
mkdir -p docs/superpowers/plans/2026-04-16-verification-artifacts
# (manually add logcat.txt, sheet-screenshot.png, device-info.txt to that dir)
git add docs/superpowers/plans/2026-04-16-verification-artifacts
git commit -m "docs: record end-to-end verification artifacts for v1 release"
```

- [ ] **Step 17.5: Distribute the APK**

Upload `app/build/outputs/apk/release/app-release.apk` to Google Drive with "anyone with the link" access. Share the link with your internal users. Instruct them to enable *Install unknown apps* on the app they'll download from, tap the APK, and follow the same onboarding flow. First flush on each user's device lands in the Sheet at the next wall-clock quarter-hour boundary after install.

---

## Final review

**Spec coverage check** — each spec section mapped to its implementing task:

| Spec § | Task(s) |
|---|---|
| §1 Purpose | (narrative only, no task) |
| §2 Goals & non-goals | (narrative only) |
| §3 Architecture | Tasks 8–12 realize the component graph |
| §4 Sample / NetworkMetrics / SheetPayload | Tasks 5, 6, 7 |
| §5.1 Sampler loop | Task 10 |
| §5.1.1 Flusher alignment, capture order, partial window, empty drain | Task 10 |
| §5.2 Percentile | Task 6 |
| §5.3 Retain-on-failure + buffer cap | Tasks 5, 10 |
| §5.4 Total-loss window | Task 6 |
| §5.5 VoIP-relevant percentiles | Task 6 (p99 computation); Task 14 (dashboard P99) |
| §6.1 OnboardingActivity | Task 13 |
| §6.2 PingService | Tasks 9, 10 |
| §6.3 SampleBuffer | Task 5 |
| §6.4 MainActivity | Task 14 |
| §6.5 BootReceiver | Task 11 |
| §7 File plan (delete/rewrite/new) | Tasks 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 13, 14 |
| §8.1 Gradle | Task 1 |
| §8.2 libs.versions.toml | Task 1 |
| §8.3 Themes | Task 3 |
| §8.4 Manifest | Task 12 |
| §9 Apps Script | Task 16 |
| §10 Keystore + signing + distribute | Tasks 15, 17 |
| §11 Verification checklist | Task 17 |
| §11a HTTP client config | Task 7 |
| §11b Logging convention | Tasks 7, 10 (tags `NM.*` used throughout) |
| §12 Risks | (narrative only; mitigations embedded in tasks) |
| §13 Future enhancements | Deliberately out of scope |

Every implementable spec requirement has a task. No placeholders, no "TODO", no "similar to Task N".

**Type consistency check:**
- `Sample(rttMs: Double?, tsMs: Long)` — identical across Tasks 5, 6, 10.
- `NetworkMetrics` 10 fields with nullable RTT-derived fields — consistent in Tasks 6, 10, 14.
- `SheetPayload` 15 fields, nullable RTT-derived + non-null `packetLossPct`/`samplesCount`/metadata — consistent in Tasks 7, 10.
- `PREF_*` constants used from Tasks 10, 13, 14 all defined in Task 4.
- `Constants.*` names match between definition (Task 4) and usage (Tasks 5, 7, 9, 10).
- `NotificationHelper.ensureChannel/buildOngoing` signatures align between Tasks 8 and 9.
- `SheetsUploader(url)` constructor used in Task 7 tests matches the default-arg constructor used in Task 10.
- Package structure: `.service.PingService` matches manifest declaration (Task 12) and imports in Tasks 11, 13.
