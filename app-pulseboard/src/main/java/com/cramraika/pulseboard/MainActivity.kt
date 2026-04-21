package com.cramraika.pulseboard

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Placeholder Activity for the Pulseboard public build.
 *
 * The shared engine (PingEngine, SampleBuffer, MetricsCalculator, SheetsUploader,
 * NetworkUtils, Sample/NetworkMetrics/SheetPayload) already lives in :core and is
 * battle-tested via the CN v1.0 deployment. v1.1 wires it into a configurable
 * foreground service here — user-supplied ping targets, webhook URL, and
 * onboarding without the CN-specific email gate.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
