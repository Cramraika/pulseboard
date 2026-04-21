package com.codingninjas.networkmonitor.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.NotificationHelper
import com.codingninjas.networkmonitor.PingTarget
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.pulseboard.core.DeviceAggregates
import com.pulseboard.core.GatewayResolver
import com.pulseboard.core.MetricsCalculator
import com.pulseboard.core.NetworkMetrics
import com.pulseboard.core.PingEngine
import com.pulseboard.core.PingResult
import com.pulseboard.core.Sample
import com.pulseboard.core.SampleBuffer
import com.pulseboard.core.ScanSnapshot
import com.pulseboard.core.SheetPayload
import com.pulseboard.core.SheetsUploader
import com.pulseboard.core.UdpDnsPinger
import com.pulseboard.core.UnregisterHandle
import com.pulseboard.core.WifiMetadataCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * Foreground service that runs 4 parallel 1 Hz sampler coroutines (one per
 * [PingTarget]) plus a wall-clock-aligned flusher that uploads all 4 targets'
 * 15-minute aggregates to the CN Sheet webhook in a single batch POST.
 *
 * See [docs/superpowers/specs/2026-04-21-voip-diagnostic-design.md] §4.3 for
 * the end-to-end flow. Key design notes carried over from v1.0:
 *   - Wall-clock alignment on :00/:15/:30/:45 so devices flush in lockstep.
 *   - Retain-on-failure: if uploadBatch fails, drained samples are prepended
 *     back to their per-target buffers so the next window's aggregation still
 *     includes them.
 *   - START_STICKY + BootReceiver restart path (BootReceiver unchanged).
 */
class PingService : Service() {

    private val tag = "NM.Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    private lateinit var gatewayResolver: GatewayResolver
    private lateinit var wifiCollector: WifiMetadataCollector
    private lateinit var udpDnsPinger: UdpDnsPinger
    private lateinit var targets: List<PingTarget>
    private lateinit var buffers: Map<PingTarget, SampleBuffer>
    private lateinit var uploader: SheetsUploader
    private var gatewayUnregister: UnregisterHandle? = null
    private var loopsStarted = false

    override fun onCreate() {
        super.onCreate()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        gatewayResolver = GatewayResolver(cm)
        wifiCollector = WifiMetadataCollector(applicationContext)
        udpDnsPinger = UdpDnsPinger(Constants.DNS_RESOLVER_IP)

        targets = listOf(
            PingTarget(
                id = "smartflo",
                resolveAddress = { Constants.SMARTFLO_IP },
                sampler = { address -> PingEngine.runPing(address, 1, Constants.PING_TIMEOUT_SEC) }
            ),
            PingTarget(
                id = "gateway",
                resolveAddress = { gatewayResolver.currentGateway() },
                sampler = { address -> PingEngine.runPing(address, 1, Constants.PING_TIMEOUT_SEC) }
            ),
            PingTarget(
                id = "cloudflare",
                resolveAddress = { Constants.CLOUDFLARE_IP },
                sampler = { address -> PingEngine.runPing(address, 1, Constants.PING_TIMEOUT_SEC) }
            ),
            PingTarget(
                id = "dns",
                resolveAddress = { Constants.DNS_RESOLVER_IP },
                // UDP-DNS pinger ignores the passed address and uses its own configured resolverIp.
                sampler = { _ -> udpDnsPinger.runQuery() }
            )
        )
        buffers = targets.associateWith { SampleBuffer(maxSize = Constants.MAX_BUFFER_SAMPLES) }

        uploader = SheetsUploader(
            webhookUrl = Constants.WEBHOOK_URL,
            connectTimeoutSec = Constants.HTTP_CONNECT_TIMEOUT_SEC,
            writeTimeoutSec = Constants.HTTP_WRITE_TIMEOUT_SEC,
            readTimeoutSec = Constants.HTTP_READ_TIMEOUT_SEC
        )

        gatewayUnregister = gatewayResolver.registerOnChange { newGateway ->
            Log.i(tag, "gateway changed → $newGateway")
            // Samplers pick up the new address on their next iteration; no explicit
            // action needed here. Logging only so field investigations can correlate.
        }

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
        Log.i(tag, "service created and foregrounded with ${targets.size} targets")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopsStarted) return START_STICKY
        loopsStarted = true
        targets.forEach { target ->
            scope.launch { samplerLoop(target) }
        }
        scope.launch { flusherLoop() }
        Log.i(tag, "${targets.size} samplers + flusher launched")
        return START_STICKY
    }

    private suspend fun samplerLoop(target: PingTarget) {
        val loopTag = "NM.Sampler.${target.id}"
        while (coroutineContext.isActive) {
            try {
                val t0 = System.currentTimeMillis()
                // CRITICAL ORDERING: Wi-Fi snapshot BEFORE the ping, so RTT and
                // BSSID/SSID describe the same network state at t0. If the device
                // roams during the ping, the stale sample self-corrects next iteration.
                val wifi = wifiCollector.snapshot()
                val address = target.resolveAddress()
                val sample: Sample = if (address == null) {
                    Sample(
                        rttMs = null, tsMs = t0,
                        target = target.id,
                        unreachable = true,
                        wifi = wifi
                    )
                } else {
                    val result: PingResult = target.sampler(address)
                    val rtt = if (result.success) result.rtts.firstOrNull() else null
                    Sample(
                        rttMs = rtt, tsMs = t0,
                        target = target.id,
                        unreachable = false,
                        wifi = wifi
                    )
                }
                buffers.getValue(target).add(sample)

                val elapsed = System.currentTimeMillis() - t0
                // Clamp to 0 — ping timeout can exceed the interval, pushing
                // (interval − elapsed) negative. Without coerceAtLeast the sampler
                // would tight-loop during a burst of timeouts.
                delay((Constants.SAMPLE_INTERVAL_MS - elapsed).coerceAtLeast(0L))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(loopTag, "sampler iteration threw", e)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(flusherTag, "flusher iteration threw", e)
            }
            delay(quarterMs)
        }
    }

    private fun runOneFlush() {
        val flusherTag = "NM.Flusher"
        val flushStartMs = System.currentTimeMillis()
        val quarterMs = Constants.FLUSH_INTERVAL_MINUTES * 60_000L

        val scanSnapshot = wifiCollector.scanSnapshot()
        val drained: Map<PingTarget, List<Sample>> = targets.associateWith { buffers.getValue(it).drain() }
        val allSamples = drained.values.flatten()
        if (allSamples.isEmpty()) {
            Log.i(flusherTag, "drained 0 samples across all targets — skip")
            return
        }

        // Duty-cycle numerator uses ONLY samples produced in the current window,
        // not retained-from-prior-failure samples — otherwise duty climbs past 1.0
        // after a failed flush because drained contains both windows' data.
        val currentWindowSamplesCount = allSamples.count { it.tsMs >= flushStartMs - quarterMs }
        val dutyCycle = currentWindowSamplesCount.toDouble() / Constants.EXPECTED_TOTAL_SAMPLES_PER_WINDOW

        val deviceAgg = MetricsCalculator.deviceLevelAggregates(allSamples)
        val windowStartMs = allSamples.minOf { it.tsMs }

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val flushSeq = prefs.getLong(Constants.PREF_FLUSH_SEQ, 0L) + 1
        prefs.edit().putLong(Constants.PREF_FLUSH_SEQ, flushSeq).apply()
        val retainedFromPrior = prefs.getInt(Constants.PREF_PENDING_RETAIN_COUNT, 0)

        val userId = prefs.getString(Constants.PREF_USER_ID, "") ?: ""
        val gatewayIp = gatewayResolver.currentGateway()
        val stickyGapDb = computeStickyGapDb(scanSnapshot, deviceAgg)

        val rows = targets.map { target ->
            val samples = drained.getValue(target)
            val metrics = MetricsCalculator.aggregate(samples)
            val gaps = MetricsCalculator.gapsCount(samples, Constants.GAP_THRESHOLD_MS)
            val unreachableTarget = samples.isNotEmpty() && samples.all { it.unreachable }
            buildPayload(
                target = target,
                metrics = metrics,
                gaps = gaps,
                unreachableTarget = unreachableTarget,
                deviceAgg = deviceAgg,
                scan = scanSnapshot,
                stickyGapDb = stickyGapDb,
                userId = userId,
                gatewayIp = gatewayIp,
                windowStartMs = windowStartMs,
                dutyCycle = dutyCycle,
                flushSeq = flushSeq,
                retainMergedCount = retainedFromPrior
            )
        }

        val ok = uploader.uploadBatch(rows)
        if (ok) {
            persistLastBatch(flushStartMs, rows)
            prefs.edit().putInt(Constants.PREF_PENDING_RETAIN_COUNT, 0).apply()
            Log.i(flusherTag, "flush ok — ${rows.size} rows, duty=${"%.2f".format(dutyCycle)}, flush_seq=$flushSeq")
        } else {
            targets.forEach { buffers.getValue(it).prepend(drained.getValue(it)) }
            prefs.edit().putInt(Constants.PREF_PENDING_RETAIN_COUNT, retainedFromPrior + rows.size).apply()
            Log.w(flusherTag, "flush failed — retained ${rows.size} rows for next window")
        }
    }

    private fun buildPayload(
        target: PingTarget,
        metrics: NetworkMetrics,
        gaps: Int,
        unreachableTarget: Boolean,
        deviceAgg: DeviceAggregates,
        scan: ScanSnapshot?,
        stickyGapDb: Int?,
        userId: String,
        gatewayIp: String?,
        windowStartMs: Long,
        dutyCycle: Double,
        flushSeq: Long,
        retainMergedCount: Int
    ): SheetPayload {
        return SheetPayload(
            windowStart = Instant.ofEpochMilli(windowStartMs).toString(),
            userId = userId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidSdk = Build.VERSION.SDK_INT,
            oemSkin = Build.MANUFACTURER.lowercase(),
            appVersion = Constants.APP_VERSION,

            target = target.id,
            gatewayIp = gatewayIp,
            unreachableTarget = unreachableTarget,

            avgRttMs = metrics.avgPing,
            minRttMs = metrics.minPing,
            maxRttMs = metrics.maxPing,
            p50RttMs = metrics.p50Ping,
            p95RttMs = metrics.p95Ping,
            p99RttMs = metrics.p99Ping,
            jitterMs = metrics.jitter,
            packetLossPct = metrics.packetLoss,

            samplesCount = metrics.samplesCount,
            reachableSamplesCount = metrics.reachableSamplesCount,
            maxRttOffsetSec = metrics.maxRttOffsetSec,

            gapsCount = gaps,
            bssidChangesCount = deviceAgg.bssidChangesCount,
            ssidChangesCount = deviceAgg.ssidChangesCount,
            rssiMin = deviceAgg.rssiMin,
            rssiAvg = deviceAgg.rssiAvg,
            rssiMax = deviceAgg.rssiMax,
            primaryBssid = deviceAgg.primaryBssid,
            primarySsid = deviceAgg.primarySsid,
            primaryFrequencyMhz = deviceAgg.primaryFrequencyMhz,
            primaryLinkSpeedMbps = deviceAgg.primaryLinkSpeedMbps,
            currentBssid = deviceAgg.currentBssid,
            currentRssi = deviceAgg.currentRssi,
            networkTypeDominant = deviceAgg.networkTypeDominant,
            vpnActive = deviceAgg.vpnActive,

            visibleApsCount = scan?.visibleApsCount,
            bestAvailableRssi = scan?.bestAvailableRssi,
            stickyClientGapDb = stickyGapDb,

            dutyCyclePct = dutyCycle,
            flushSeq = flushSeq,
            retainMergedCount = retainMergedCount
        )
    }

    /**
     * `best_available_rssi − current_rssi`. Both values are in negative-dB
     * space (less-negative = stronger), so the diff is positive-dB-strength
     * the device could have gained by roaming but didn't. >15 dB is the
     * classic sticky-client threshold.
     */
    private fun computeStickyGapDb(scan: ScanSnapshot?, deviceAgg: DeviceAggregates): Int? {
        val best = scan?.bestAvailableRssi ?: return null
        val current = deviceAgg.currentRssi ?: return null
        return best - current
    }

    private fun persistLastBatch(flushMs: Long, rows: List<SheetPayload>) {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_LAST_BATCH_JSON, gson.toJson(rows))
            .putLong(Constants.PREF_LAST_FLUSH_MS, flushMs)
            .apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        gatewayUnregister?.invoke()
        scope.cancel()
        Log.i(tag, "service destroyed")
        super.onDestroy()
    }
}
