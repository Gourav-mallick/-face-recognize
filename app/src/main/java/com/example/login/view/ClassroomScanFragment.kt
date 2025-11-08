package com.example.login.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.login.R
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.example.login.api.ApiClient
import com.example.login.db.dao.AppDatabase
import com.example.login.repository.DataSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.login.api.ApiService
import com.example.login.db.entity.Session


class ClassroomScanFragment : Fragment() {

    private lateinit var tvSyncStatus: TextView

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "UPDATE_UNSUBMITTED_COUNT") {
                refreshUnsubmittedSessions()
            }
        }
    }


    companion object {
        fun newInstance() = ClassroomScanFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = inflater.inflate(R.layout.fragment_classroom_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvSyncStatus = view.findViewById(R.id.tvSyncStatus)
        val tvUnsubmittedCount = view.findViewById<TextView>(R.id.tvUnsubmittedCount)
        val prefs = requireContext().getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
        val tvLastSync = view.findViewById<TextView>(R.id.tvLastSync)

        val lastSync = prefs.getString("last_sync_time", null)
        if (lastSync != null) {
            tvLastSync.text = "Last Sync: $lastSync"
        }

        // inside onViewCreated
        val tvManualDataSync = view.findViewById<Button>(R.id.tvManualDataSync)
        tvManualDataSync.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Sync Data")
                .setMessage("Do you want to sync data from the server and update your local database?")
                .setPositiveButton("Yes") { _, _ ->
                    showAuthDialogForSync()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

// Listen for broadcast updates
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val time = intent?.getStringExtra("time") ?: return
                tvLastSync.text = "Last Sync: $time"
            }
        }
        val filter = IntentFilter("SYNC_UPDATE")
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(receiver, filter)
        }


        // show offline hours
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val lastUptime = prefs.getLong("last_sync_uptime", 0L)
                val lastSyncStr = prefs.getString("last_sync_time", null)

                if (lastUptime > 0 && lastSyncStr != null) {
                    val diffMillis = SystemClock.elapsedRealtime() - lastUptime
                    val diffHours = (diffMillis / (1000 * 60 * 60)).toInt()

                    if (diffHours >= 24) {
                        tvLastSync.text = "⚠️ Time expired — please sync"
                        tvLastSync.setTextColor(android.graphics.Color.RED)
                    } else {
                        //  tvSyncStatus.text = "Working offline for $diffHours hrs"
                        // tvSyncStatus.setTextColor(android.graphics.Color.WHITE)
                    }
                } else {
                    // tvSyncStatus.text = "Last Sync: --"
                }
                delay(60_000)
            }


        }

        // 🕓 Show count of unsubmitted (active) sessions
        refreshUnsubmittedSessions()

// 🔔 Listen for broadcast to refresh count after session ends
        val updateFilter = IntentFilter("UPDATE_UNSUBMITTED_COUNT")
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                updateReceiver,
                updateFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            requireContext().registerReceiver(updateReceiver, updateFilter)
        }


        // 🔹 Disable back press (both button and gesture)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Toast.makeText(
                        requireContext(),
                        "Back is disabled on this screen",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )


        tvSyncStatus.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Confirm Sync")
                .setMessage("Do you want to send pending attendance to the server?")
                .setPositiveButton("Yes") { _, _ ->
                    val intent = Intent(requireContext(), SyncAttendanceToServer::class.java)
                    startActivity(intent)


                }
                .setNegativeButton("No", null)
                .show()
        }


    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun showAuthDialogForSync() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_auth_sync, null)
        val edtUsername = dialogView.findViewById<EditText>(R.id.edtUsername)
        val edtPassword = dialogView.findViewById<EditText>(R.id.edtPassword)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmit)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val enteredUser = edtUsername.text.toString().trim()
            val enteredPass = edtPassword.text.toString().trim()
            val prefs = requireContext().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val savedUser = prefs.getString("username", "")
            val savedPass = prefs.getString("password", "")

            if (enteredUser == savedUser && enteredPass == savedPass) {
                dialog.dismiss()
                showProgressAndSync()
            } else {
                Toast.makeText(requireContext(), "Invalid credentials!", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }


    private fun showProgressAndSync() {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Syncing Data")
            .setMessage("Please wait while data is being synced...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            // Simulate loading delay for 3 seconds (visual feedback)
            delay(3000)

            val prefs = requireContext().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("baseUrl", "") ?: ""
            val instIds = prefs.getString("selectedInstituteIds", "") ?: ""
            val HASH = "trr36pdthb9xbhcppyqkgbpkq"

            if (baseUrl.isBlank() || instIds.isBlank()) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "Missing institute or URL info",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
                baseUrl.removeSuffix("/") + "///"
            } else {
                "$baseUrl///"
            }

            try {
                val retrofit = ApiClient.getClient(normalizedBaseUrl, HASH)
                val apiService = retrofit.create(ApiService::class.java)
                val db = AppDatabase.getDatabase(requireContext())
                val repository = DataSyncRepository(requireContext())

                val studentsOk = repository.fetchAndSaveStudents(apiService, db, instIds)
                val teachersOk = repository.fetchAndSaveTeachers(apiService, db, instIds)
                val subjectsOk = repository.syncSubjectInstances(apiService, db)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (studentsOk && teachersOk && subjectsOk) {
                        Toast.makeText(
                            requireContext(),
                            " Sync Successful , Data synced and updated in local database.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "⚠️ Some data failed to sync. Try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "❌ Sync failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun refreshUnsubmittedSessions() {
        val tvUnsubmittedCount = view?.findViewById<TextView>(R.id.tvUnsubmittedCount) ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val openSessions = db.sessionDao().getAllSessions()
                    .filter { it.endTime.isNullOrEmpty() }

                if (openSessions.isNotEmpty()) {
                    tvUnsubmittedCount.visibility = View.VISIBLE
                    tvUnsubmittedCount.text = "🕓 Unsubmitted Sessions: ${openSessions.size}"

                    // 🔹 Add click listener to show details
                    tvUnsubmittedCount.setOnClickListener {
                        showUnsubmittedSessionPopup(openSessions)
                    }
                } else {
                    tvUnsubmittedCount.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun showUnsubmittedSessionPopup(openSessions: List<Session>) {
        if (openSessions.isEmpty()) return

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Unsubmitted Sessions")

        // Use coroutine to safely call suspend DAO method
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val message = StringBuilder()

            for ((index, session) in openSessions.withIndex()) {
                val teacherId = session.teacherId ?: "N/A"
                val teacherName = withContext(Dispatchers.IO) {
                    db.teachersDao().getTeacherNameById(teacherId)
                } ?: "Unknown"

                message.append("${index + 1}. ID-$teacherId : $teacherName\n\n")
            }

            withContext(Dispatchers.Main) {
                builder.setMessage(message.toString().trim())
                builder.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                builder.show()
            }
        }
    }
}

