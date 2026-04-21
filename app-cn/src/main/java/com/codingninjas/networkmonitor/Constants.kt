package com.codingninjas.networkmonitor

object Constants {
    // Webhook (unchanged from existing deployment; points at CN's Sheet Apps Script)
    const val WEBHOOK_URL = "https://script.google.com/a/macros/codingninjas.com/s/AKfycbwc1kkq2KT2lLNrymksf289SozEGh8jI-_sVhx9xhHMZWTUpDwLAI90uRFHmWEN2gfXMQ/exec"

    // v1.1 ping targets
    const val SMARTFLO_IP = "14.97.20.3"          // Tata Smartflo VoIP endpoint (AHM region)
    const val CLOUDFLARE_IP = "1.1.1.1"           // generic internet reachability control
    const val DNS_RESOLVER_IP = "1.1.1.1"         // target for UDP-DNS probe
    // v1.0 single-target constant — retained so v1.0-compat PingService keeps
    // compiling during the intermediate commits. Removed in the step-8 refactor.
    const val PING_TARGET = "8.8.8.8"
    const val PING_TIMEOUT_SEC = 2

    // Sampling + flush cadence
    const val SAMPLE_INTERVAL_MS = 1000L
    const val FLUSH_INTERVAL_MINUTES = 15L
    const val MAX_BUFFER_SAMPLES = 5400   // 90 minutes of samples, drop-oldest on overflow

    // v1.1 gap + duty-cycle thresholds
    const val GAP_THRESHOLD_MS = 3000L                            // > 3s between samples = gap
    const val EXPECTED_SAMPLES_PER_TARGET_PER_WINDOW = 900        // 15 min × 60 s × 1 Hz
    const val EXPECTED_TOTAL_SAMPLES_PER_WINDOW = 3600            // 4 targets × 900

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

    // v1.0 prefs keys — retained so v1.0-compat PingService + MainActivity still
    // compile during the intermediate commits. Removed in step 9 alongside the
    // MainActivity UI rewrite.
    const val PREF_LAST_RESULT = "last_result"
    const val PREF_LAST_UPDATE_TIME = "last_update_time"
    const val PREF_LAST_NETWORK_TYPE = "last_network_type"

    // v1.1 prefs keys — populated by the new 4-sampler flusher (step 8) and read
    // by the last-sent-summary MainActivity (step 9).
    const val PREF_LAST_BATCH_JSON = "last_batch_json"            // JSON of List<SheetPayload>
    const val PREF_LAST_FLUSH_MS = "last_flush_ms"                // Long
    const val PREF_FLUSH_SEQ = "flush_seq"                        // Long, persists across restarts
    const val PREF_PENDING_RETAIN_COUNT = "pending_retain_count"  // Int, rows retained from prior failures

    // Metadata — bumped to "1.1" in step 10 alongside versionCode 2
    const val APP_VERSION = "1.0"
}
