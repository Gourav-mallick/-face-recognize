package com.example.login.utility

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import com.example.login.db.dao.AppDatabase
import com.example.login.repository.DataSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AutoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "") ?: ""
        val instIds = prefs.getString("selectedInstituteIds", "") ?: ""
        val HASH = "trr36pdthb9xbhcppyqkgbpkq"

        if (baseUrl.isBlank() || instIds.isBlank()) {
            Log.e("AutoSyncWorker", "Missing institute or URL info")
            return Result.failure()
        }

        // Only run when network is available
        if (!isNetworkAvailable(applicationContext)) {
            Log.w("AutoSyncWorker", "No internet connection. Will retry later.")
            return Result.retry()
        }

        val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
            baseUrl.removeSuffix("/") + "///"
        } else {
            "$baseUrl///"
        }

        try {
            val retrofit = ApiClient.getClient(normalizedBaseUrl, HASH)
            val apiService = retrofit.create(ApiService::class.java)
            val db = AppDatabase.getDatabase(applicationContext)
            val repository = DataSyncRepository(applicationContext)

            val studentsOk = repository.fetchAndSaveStudents(apiService, db, instIds)
            val teachersOk = repository.fetchAndSaveTeachers(apiService, db, instIds)
         //   val subjectsOk = repository.syncSubjectInstances(apiService, db)

            val success = studentsOk && teachersOk

            if (success) {
                recordSyncTime()
                sendBroadcastUpdate(applicationContext)
                Log.i("AutoSyncWorker", "✅ Auto sync successful")
                return Result.success()
            } else {
                Log.w("AutoSyncWorker", "⚠️ Some data failed to sync")
                return Result.retry()
            }

        } catch (e: Exception) {
            Log.e("AutoSyncWorker", "❌ Sync failed: ${e.message}", e)
            return Result.retry()
        }
    }

    private fun isNetworkAvailable(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    private suspend fun recordSyncTime() = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
        val now = Date()
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
        val formatted = sdf.format(now)
        prefs.edit()
            .putString("last_sync_time", formatted)
            .putLong("last_sync_uptime", android.os.SystemClock.elapsedRealtime())
            .apply()
    }

    private fun sendBroadcastUpdate(context: Context) {
        val prefs = applicationContext.getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
        val time = prefs.getString("last_sync_time", "") ?: ""
        val intent = Intent("SYNC_UPDATE").putExtra("time", time)
        context.sendBroadcast(intent)
    }
}
