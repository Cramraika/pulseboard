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
