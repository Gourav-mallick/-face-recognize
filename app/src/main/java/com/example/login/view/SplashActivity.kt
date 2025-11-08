package com.example.login.view

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.example.login.R
import kotlin.apply
import kotlin.jvm.java
import kotlin.text.trimIndent

class SplashActivity : AppCompatActivity() {

    private lateinit var logo: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        logo = findViewById(R.id.logo)

        playLogoAnimation()
        saveDeviceInfo()
        navigateToNextScreenWithDelay()
    }


    // Play fade-in animation for splash logo
    private fun playLogoAnimation() {
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 2000 }
        logo.startAnimation(fadeIn)
    }


    // Get and save device info into SharedPreferences
    private fun saveDeviceInfo() {
        val deviceInfo = getSystemDetail()
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("device_info", deviceInfo)
            apply()
        }
        Log.d("SplashActivity", "Device Info: \n$deviceInfo")
    }


    // Delay navigation to next activity
    private fun navigateToNextScreenWithDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToNextScreen()
        }, 1500)
    }

    // Navigate to next screen
    private fun navigateToNextScreen() {
        startActivity(Intent(this, CheckConfigActivity::class.java))
        finish()
    }

    // Function: Get device details
    @SuppressLint("HardwareIds")
    private fun getSystemDetail(): String {
        return """
            Brand: ${Build.BRAND}
            DeviceID: ${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)}
            Model: ${Build.MODEL}
            ID: ${Build.ID}
            SDK: ${Build.VERSION.SDK_INT}
            Manufacture: ${Build.MANUFACTURER}
            User: ${Build.USER}
            Type: ${Build.TYPE}
            Base: ${Build.VERSION_CODES.BASE}
            Incremental: ${Build.VERSION.INCREMENTAL}
            Board: ${Build.BOARD}
            Host: ${Build.HOST}
            FingerPrint: ${Build.FINGERPRINT}
            Version Code: ${Build.VERSION.RELEASE}
        """.trimIndent()
    }
}
