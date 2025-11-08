package com.example.login.utility

import android.content.Context
import android.util.Log
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DatabaseCleanupUtils {

    /**
     * Delete all attendance records where syncStatus = 'complete'
     */
    suspend fun deleteSyncedAttendances(context: Context): Int {
        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val deletedCount = db.attendanceDao().deleteSyncedAttendances()
                Log.d("DB_CLEANUP", "Deleted $deletedCount synced attendance records")
                deletedCount
            } catch (e: Exception) {
                Log.e("DB_CLEANUP", "Error deleting attendance: ${e.message}", e)
                0
            }
        }
    }

    /**
     * Delete all session records where syncStatus = 'complete'
     */
    suspend fun deleteSyncedSessions(context: Context): Int {
        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val deletedCount = db.sessionDao().deleteSyncedSessions()
                Log.d("DB_CLEANUP", "Deleted $deletedCount synced session records")
                deletedCount
            } catch (e: Exception) {
                Log.e("DB_CLEANUP", "Error deleting sessions: ${e.message}", e)
                0
            }
        }
    }

    /**
     * ✅ Optional combined cleanup (calls both above)
     */
    suspend fun deleteAllSyncedData(context: Context) {
        withContext(Dispatchers.IO) {
            val att = deleteSyncedAttendances(context)
            val ses = deleteSyncedSessions(context)
            Log.d("DB_CLEANUP", "Total deleted: Attendance=$att, Session=$ses")
        }
    }
}
