package com.example.login

import android.app.Application
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.util.Log
import com.sis.notification.NetworkReceiver

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Log.e("AUTO_SYNC", "MyApp.onCreate() → Application class loaded")

        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(NetworkReceiver(), filter)

        Log.e("AUTO_SYNC", "NetworkReceiver registered in MyApp")
    }
}
