package com.sis.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.login.db.dao.AppDatabase
import com.example.login.repository.DataSyncRepository
import com.example.login.utility.CheckNetworkAndInternetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        Log.e("AUTO_SYNC", "NetworkReceiver triggered")

        // Quick check: Is Android reporting network available?
        val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(context)

        if (!hasNetwork) {
            Log.e("AUTO_SYNC", "No network available → Skip auto-sync.")
            return
        }

        // Do internet verification (ping-check)
        CoroutineScope(Dispatchers.IO).launch {

            val hasInternet = CheckNetworkAndInternetUtils.hasInternetAccess()
            if (!hasInternet) {
                Log.e("AUTO_SYNC", "Network available, but NO Internet → Skip auto-sync.")
                return@launch
            }

            Log.e("AUTO_SYNC", "Internet available → Starting auto sync...")

            val repository = DataSyncRepository(context)
            val db = AppDatabase.getDatabase(context)

            // 🔹 1. SYNC PENDING STUDENT SCHEDULES (existing logic)
            try {
                val pendingStudents = db.pendingScheduleDao().getPendingSchedules()
                if (pendingStudents.isNotEmpty()) {
                    Log.e("AUTO_SYNC", "Pending student schedules = ${pendingStudents.size}")
                    repository.syncPendingStudentSchedules(context)
                } else {
                    Log.e("AUTO_SYNC", "No pending student schedules.")
                }
            } catch (e: Exception) {
                Log.e("AUTO_SYNC", "Error syncing student schedules: ${e.message}")
            }

            // 🔹 2. SYNC PENDING TEACHER ALLOCATION (new logic)
            try {
                val pendingTeacher = db.pendingTeacherAllocationDao().getPending()
                if (pendingTeacher.isNotEmpty()) {
                    Log.e("AUTO_SYNC", "Pending teacher allocations = ${pendingTeacher.size}")
                    repository.syncPendingTeacherAllocation(context)
                } else {
                    Log.e("AUTO_SYNC", "No pending teacher allocations.")
                }
            } catch (e: Exception) {
                Log.e("AUTO_SYNC", "Error syncing teacher allocations: ${e.message}")
            }

            Log.e("AUTO_SYNC", "Auto sync completed.")
        }
    }
}
