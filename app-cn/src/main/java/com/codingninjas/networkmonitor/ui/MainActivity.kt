package com.codingninjas.networkmonitor.ui

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pulseboard.core.SheetPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CN's dashboard is deliberately minimal — all diagnostic pivoting happens in
 * the Sheet. The on-device UI proves "it's still running" and shows a last-sent
 * summary per target so a user on Slack can screenshot one panel that the
 * engineer can read at a glance.
 *
 * Reads PREF_LAST_BATCH_JSON (written by PingService after each successful
 * flush), Gson-decodes the List<SheetPayload>, and renders one monospace line
 * per target. No auto-refresh — re-enters on Activity resume.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvGreeting: TextView
    private lateinit var tvLastSentHeader: TextView
    private lateinit var tvSmartflo: TextView
    private lateinit var tvGateway: TextView
    private lateinit var tvCloudflare: TextView
    private lateinit var tvDns: TextView
    private lateinit var tvFooter: TextView

    private val gson = Gson()
    private val batchType = object : TypeToken<List<SheetPayload>>() {}.type

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvGreeting = findViewById(R.id.tvGreeting)
        tvLastSentHeader = findViewById(R.id.tvLastSentHeader)
        tvSmartflo = findViewById(R.id.tvSmartflo)
        tvGateway = findViewById(R.id.tvGateway)
        tvCloudflare = findViewById(R.id.tvCloudflare)
        tvDns = findViewById(R.id.tvDns)
        tvFooter = findViewById(R.id.tvFooter)

        val email = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_USER_ID, "") ?: ""
        tvGreeting.text = "Hi, $email"
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val batchJson = prefs.getString(Constants.PREF_LAST_BATCH_JSON, null)
        val lastFlushMs = prefs.getLong(Constants.PREF_LAST_FLUSH_MS, 0L)

        if (batchJson == null || lastFlushMs == 0L) {
            renderEmpty()
            return
        }

        val rows: List<SheetPayload>? = try {
            gson.fromJson(batchJson, batchType)
        } catch (_: Exception) {
            null
        }
        if (rows.isNullOrEmpty()) {
            renderEmpty()
            return
        }

        val byTarget = rows.associateBy { it.target }
        tvLastSentHeader.text = "Last sent ${formatTime(lastFlushMs)}"
        tvSmartflo.text = formatRow("smartflo",   byTarget["smartflo"])
        tvGateway.text = formatRow("gateway",    byTarget["gateway"])
        tvCloudflare.text = formatRow("cloudflare", byTarget["cloudflare"])
        tvDns.text = formatRow("dns",        byTarget["dns"])

        val first = rows.first()
        val nextFlushMs = lastFlushMs + Constants.FLUSH_INTERVAL_MINUTES * 60_000L
        tvFooter.text = buildFooter(first, nextFlushMs)
    }

    private fun renderEmpty() {
        tvLastSentHeader.text = "No data yet"
        tvSmartflo.text = "smartflo:   —"
        tvGateway.text = "gateway:    —"
        tvCloudflare.text = "cloudflare: —"
        tvDns.text = "dns:        —"
        tvFooter.text = "First flush completes at the next 15-minute wall-clock mark."
    }

    private fun formatRow(label: String, row: SheetPayload?): String {
        if (row == null) return "${label.padEnd(11)} —"
        if (row.unreachableTarget == true) return "${label.padEnd(11)} unreachable"
        val p95 = row.p95RttMs?.let { "${it.toInt()}ms" } ?: "—"
        val jitter = row.jitterMs?.let { "${it.toInt()}ms" } ?: "—"
        val loss = row.packetLossPct?.let { "%.1f%%".format(it) } ?: "—"
        val samples = row.samplesCount
        // Align columns using fixed-width monospace.
        return "%-11s p95 %-7s jitter %-7s loss %-6s (%d samples)".format(
            "$label:", p95, jitter, loss, samples
        )
    }

    private fun buildFooter(row: SheetPayload, nextFlushMs: Long): String {
        val nextFlush = formatTime(nextFlushMs)
        val ssid = row.primarySsid ?: "(no wifi)"
        val rssi = row.currentRssi?.let { " ($it dBm)" } ?: ""
        val freq = row.primaryFrequencyMhz?.let {
            if (it > 4000) " 5GHz" else if (it > 0) " 2.4GHz" else ""
        } ?: ""
        val duty = row.dutyCyclePct?.let { "%.0f%%".format(it * 100) } ?: "—"
        return "Next flush $nextFlush • Wi-Fi: $ssid$freq$rssi • duty $duty"
    }

    private fun formatTime(tsMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(tsMs))
}
