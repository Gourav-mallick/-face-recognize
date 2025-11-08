package com.example.login.view

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.login.R
import kotlin.jvm.java
import kotlin.text.isNullOrEmpty

class CheckConfigActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var layoutContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_config)

        progressBar = findViewById(R.id.progressBarCheck)
        statusText = findViewById(R.id.textStatus)
        layoutContainer = findViewById(R.id.layoutCheckContainer)

        // Initial message
        statusText.text = "Checking configuration..."
        progressBar.visibility = View.VISIBLE

        // Simulate slight delay for UX (e.g. reading prefs)
        Handler(Looper.getMainLooper()).postDelayed({
            checkAppConfiguration()
        }, 1500)
    }

    private fun checkAppConfiguration() {
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", null)
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)
        val instituteId = prefs.getString("selectedInstituteIds", null) //&& !instituteId.isNullOrEmpty()

        when {
            // All steps done: go to Attendance
            !baseUrl.isNullOrEmpty() && !username.isNullOrEmpty() &&
                    !password.isNullOrEmpty() && !instituteId.isNullOrEmpty() -> {
                statusText.text = "Configuration found! Redirecting..."
                progressBar.visibility = View.VISIBLE
                Handler(Looper.getMainLooper()).postDelayed({
                    startActivity(Intent(this, AttendanceActivity::class.java))
                    finish()
                }, 1000)
            }
            // Else: go to Login
            else -> {
                statusText.text = "Configuration missing. Redirecting to Login..."
                progressBar.visibility = View.VISIBLE
                Handler(Looper.getMainLooper()).postDelayed({
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }, 1500)
            }
        }
    }
}

