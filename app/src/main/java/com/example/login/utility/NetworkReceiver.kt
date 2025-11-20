package com.example.login.utility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.login.db.dao.AppDatabase
import com.example.login.repository.DataSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.e("AUTO_SYNC", "NetworkReceiver triggered by: ${intent.action}")

        val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(context)
     //   val hasInternet = CheckNetworkAndInternetUtils.hasInternetAccess()

        Log.e("AUTO_SYNC", "Network available = $hasNetwork")

        if (hasNetwork ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val pendingCount = db.pendingScheduleDao().getPendingSchedules().size

                    Log.e("AUTO_SYNC", "Pending rows in DB = $pendingCount")

                    if (pendingCount > 0) {
                        Log.e("AUTO_SYNC", "→ Starting syncPendingStudentSchedules()")
                        DataSyncRepository(context).syncPendingStudentSchedules(context)
                    } else {
                        Log.e("AUTO_SYNC", "→ No pending rows. Nothing to sync.")
                    }
                } catch (e: Exception) {
                    Log.e("AUTO_SYNC", "Receiver crash: ${e.message}")
                }
            }
        } else {
            Log.e("AUTO_SYNC", "Skipping sync — network/internet NOT available")
        }
    }

}



