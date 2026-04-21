package com.codingninjas.networkmonitor.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.R
import com.pulseboard.core.NetworkMetrics
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
