package com.example.login.utility

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.ConnectivityManager

class AutoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
        val now = Date()
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
        val formatted = sdf.format(now)


        // Save last sync with uptime
        prefs.edit()
            .putString("last_sync_time", formatted)
            .putLong("last_sync_uptime", android.os.SystemClock.elapsedRealtime()) // 👈 must be there
            .apply()


        // Optional: network check + sync logic
        if (isNetworkAvailable(applicationContext)) {
            // call API or sync method
        }

        // Notify UI
        sendBroadcastUpdate(applicationContext, formatted)
        return Result.success()
    }

    private fun isNetworkAvailable(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun sendBroadcastUpdate(context: Context, time: String) {
        val intent = Intent("SYNC_UPDATE")
        intent.putExtra("time", time)
        context.sendBroadcast(intent)
    }
}
