package com.codingninjas.networkmonitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.codingninjas.networkmonitor.ui.MainActivity

class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val existingId = prefs.getString(Constants.PREF_USER_ID, "") ?: ""
        if (existingId.isNotBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        val etName = findViewById<EditText>(R.id.etName)
        val btnStart = findViewById<Button>(R.id.btnStart)

        btnStart.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.length < 2) {
                Toast.makeText(
                    this,
                    "Please enter at least 2 characters",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString(Constants.PREF_USER_ID, name)
                .apply()
            WorkScheduler.schedule(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
