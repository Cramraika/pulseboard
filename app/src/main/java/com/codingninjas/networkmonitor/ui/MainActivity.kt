package com.codingninjas.networkmonitor.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.NetworkMetrics
import com.codingninjas.networkmonitor.NetworkUtils
import com.codingninjas.networkmonitor.OnboardingActivity
import com.codingninjas.networkmonitor.R
import com.codingninjas.networkmonitor.WorkScheduler
import com.google.gson.Gson

class MainActivity : AppCompatActivity() {

    private lateinit var tvGreeting: TextView
    private lateinit var tvAvg: TextView
    private lateinit var tvBest: TextView
    private lateinit var tvWorst: TextView
    private lateinit var tvJitter: TextView
    private lateinit var tvLoss: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var tvNoData: TextView

    private companion object {
        const val MENU_CHANGE_NAME = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvGreeting = findViewById(R.id.tvGreeting)
        tvAvg = findViewById(R.id.tvAvg)
        tvBest = findViewById(R.id.tvBest)
        tvWorst = findViewById(R.id.tvWorst)
        tvJitter = findViewById(R.id.tvJitter)
        tvLoss = findViewById(R.id.tvLoss)
        tvNetwork = findViewById(R.id.tvNetwork)
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        tvNoData = findViewById(R.id.tvNoData)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(Constants.PREF_USER_ID, "") ?: ""
        tvGreeting.text = "Hi, $name"
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(Constants.PREF_LAST_RESULT, null)
        val lastUpdate = prefs.getString("last_update_time", null)

        if (json != null && lastUpdate != null) {
            val metrics = Gson().fromJson(json, NetworkMetrics::class.java)
            tvAvg.text = metrics.avgPing.toString()
            tvBest.text = metrics.bestPing.toString()
            tvWorst.text = metrics.worstPing.toString()
            tvJitter.text = metrics.jitter.toString()
            tvLoss.text = metrics.packetLoss.toString()
            tvNetwork.text = NetworkUtils.getNetworkType(this)
            tvLastUpdate.text = "Last updated: $lastUpdate"
            tvNoData.visibility = View.GONE
        } else {
            tvAvg.text = "—"
            tvBest.text = "—"
            tvWorst.text = "—"
            tvJitter.text = "—"
            tvLoss.text = "—"
            tvNetwork.text = "—"
            tvLastUpdate.text = "Last updated: —"
            tvNoData.visibility = View.VISIBLE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CHANGE_NAME, 0, "Change Name")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CHANGE_NAME) {
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            WorkScheduler.cancel(this)
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
