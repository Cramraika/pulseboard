package com.vagarylabs.pulseboard

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

/**
 * Placeholder Activity for the Pulseboard public build.
 *
 * The shared engine (PingEngine, SampleBuffer, MetricsCalculator, SheetsUploader,
 * NetworkUtils, Sample/NetworkMetrics/SheetPayload) already lives in :core and is
 * battle-tested via the CN v1.0 deployment. v1.1 wires it into a configurable
 * foreground service here — user-supplied ping targets, webhook URL, and
 * onboarding without the CN-specific email gate.
 *
 * Until v1.1 ships, the screen also surfaces the desktop companion
 * (pulseboard-desktop) so users who install the stub and need actionable
 * diagnostics today have a direct path to a tool that works.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<LinearLayout>(R.id.companionCard).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.companion_url).toUri()))
        }
    }
}
