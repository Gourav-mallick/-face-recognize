package com.example.login.view

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.login.databinding.ActivityAttendanceOverviewBinding
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.launch
import androidx.activity.OnBackPressedCallback
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import com.example.login.db.entity.Attendance
import com.example.login.utility.DatabaseCleanupUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception

class AttendanceOverviewActivity : ComponentActivity() {

    private lateinit var binding: ActivityAttendanceOverviewBinding
    private lateinit var db: AppDatabase
    private lateinit var selectedClasses: List<String>
    private lateinit var sessionId: String

    // 🔹 Track whether back press is disabled
    private var backDisabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()
        sessionId = intent.getStringExtra("SESSION_ID") ?: ""

        // ✅ Disable back press & back gesture for this screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backDisabled) {
                    // Do nothing — block back press
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadOverviewData()

        binding.btnSubmitAttendance.setOnClickListener {
            submitAttendanceForSession()
   /*
          val intent = Intent(this, AttendanceActivity::class.java)
            startActivity(intent)
            finish()

    */
        }
    }

    private fun loadOverviewData() {
        lifecycleScope.launch {
            val classSummaries = mutableListOf<ClassOverviewData>()

            for (classId in selectedClasses) {
                val students = db.studentsDao().getAllStudents().filter { it.classId == classId }
                val attendance = db.attendanceDao().getAttendancesForClass(sessionId, classId)

                val totalStudents = students.size
                val presentCount = attendance.count { it.status == "P" }
                val absentCount = totalStudents - presentCount
          /*
                val presentStudents = students.filter { s ->
                    attendance.any { it.studentId == s.studentId && it.status == "P" }
                }.map { it.studentName }

          */


                classSummaries.add(
                    ClassOverviewData(
                        className = classId,
                        totalStudents = totalStudents,
                        presentCount = presentCount,
                        absentCount = absentCount,
                     //   presentStudents = presentStudents,

                    )
                )
            }

            val adapter = AttendanceOverviewAdapter(classSummaries) { className ->
                Toast.makeText(this@AttendanceOverviewActivity, "Edit clicked for $className", Toast.LENGTH_SHORT).show()
            }

            binding.recyclerViewOverview.layoutManager = LinearLayoutManager(this@AttendanceOverviewActivity)
            binding.recyclerViewOverview.adapter = adapter
        }
    }


    private fun submitAttendanceForSession() {
        lifecycleScope.launch {

            // Show spinner
            binding.progressBar.visibility = View.VISIBLE
            delay(2000) // show for 2s

            try {
                val attendanceList = db.attendanceDao().getAttendanceBySessionId(sessionId)
              //  Log.d("AttendanceOverview", "Attendance list: $attendanceList")

                if (attendanceList.isEmpty()) {
                    binding.progressBar.visibility = View.GONE
                 //   showPopup("No attendance found for this session.")
                    Log.d("AttendanceOverview", "No attendance found for this session.")
                    return@launch
                }

                // Get baseUrl & hash from SharedPreferences
                val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                val baseUrl = prefs.getString("baseUrl", "")!!
              //  val hash = "trr36pdthb9xbhcppyqkgbpkq"
                val hash=prefs.getString("hash", "")!!


                val apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

                // Prepare JSON payload
                val attArray = JSONArray()
                for (att in attendanceList) {
                    attArray.put(mapAttendanceToApiFormat(att))
                }

                val requestBodyJson = JSONObject().apply {
                    put("attParamDataObj", JSONObject().apply {
                        put("attDataArr", attArray)
                        put("attAttachmentArr", JSONArray())
                        put("attendanceMethod", "periodDayWiseAttendance")
                        put("loggedInUsrId", "1")
                    })
                }

                Log.d("SYNC_REQUEST", requestBodyJson.toString())

                val mediaType = MediaType.parse("application/json; charset=utf-8")
                val requestBody = RequestBody.create(mediaType, requestBodyJson.toString())

                val response = apiService.postAttendanceSync(
                    r = "api/v1/Att/ManageMarkingGlobalAtt",
                    requestBody = requestBody
                )

                binding.progressBar.visibility = View.GONE

                if (response.isSuccessful && response.body() != null) {
                    val bodyString = response.body()!!.string()
                    Log.d("SYNC_RESPONSE", bodyString)

                    try {
                        val json = JSONObject(bodyString)
                        val collection = json.optJSONObject("collection")
                        val responseObj = collection?.optJSONObject("response")
                        val apiStatus = responseObj?.optString("status", "FAILED") ?: "FAILED"
                        val apiMsgArray = responseObj?.optJSONArray("msgAr")
                        val msg = apiMsgArray?.optString(0) ?: "Attendance synced successfully"

                        if (apiStatus.equals("SUCCESS", ignoreCase = true)) {
                            // Delete Attendance records from DB & Session if it successfully sent to server
                            db.attendanceDao().updateSyncStatusBySession(sessionId, "complete")
                            db.sessionDao().updateSessionSyncStatusToComplete(sessionId, "complete")

                            DatabaseCleanupUtils.deleteSyncedAttendances(this@AttendanceOverviewActivity)
                            DatabaseCleanupUtils.deleteSyncedSessions(this@AttendanceOverviewActivity)

                            withContext(Dispatchers.Main) {
                                showPopupWithOk(msg)
                            }


                        } else {
                            withContext(Dispatchers.Main) {
                                showPopupWithOk("Attendance saved locally. You can sync later.")
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showPopupWithOk("Attendance saved locally. You can sync later.")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showPopupWithOk("Attendance saved locally. You can sync later.")
                    }
                }



            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                withContext(Dispatchers.Main) {
                    showPopupWithOk("Server not reachable. Attendance saved locally, will sync later.")
                }
            }
        }
    }


    private fun mapAttendanceToApiFormat(att: Attendance): JSONObject {
        val date = att.date
        val year = date.split("-")[0]
        val startTime = att.startTime
        val endTime = att.endTime
        val dataStartTime = "$date $startTime:00"
        val dataEndTime = "$date $endTime:00"

        return JSONObject().apply {
            put("studentId", att.studentId)
            put("instId", att.instId)
            put("instShortName", att.instShortName ?: "")
            put("academicYear",  year)
            put("classId", att.classId)
            put("classShortName", att.classShortName ?: "")
            put("subjectId", att.subjectId ?: "")
            put("subjectCode", att.subjectId ?: "")
            put("subjectShortName", att.subjectTitle ?: "")
            put("courseId", att.courseId ?: "")
            put("courseShortName", att.courseShortName ?: "")
            put("cpId", att.cpId ?: "")
            put("cpShortName", "")
            put("mpId", att.mpId ?: "")
            put("mpShortName", att.mpLongTitle ?: "")
            put("attDate", att.date)
            put("attSchoolPeriodStartTime", att.startTime)
            put("attSchoolPeriodEndTime", att.endTime)
            put("period", att.period)
            put("status", att.status)
            // You can extend more mappings as per your actual backend requirement
            put("studentClass", att.classShortName ?: "")
            put("attCodetitle", "present")
            put("courseSelectionMode","")
            put("stfId",att.teacherId)
            put("stfFML","")
            put("studId",att.studentId)
            put("studfFML","")
            put("studfLFM","")
            put("studentName",att.studentName)
            put("studAltId",att.atteId)
            put("studRollNo","")
            put("int_rollNo","")
            put("attCycleId","")
            put("attSessionId",att.sessionId)
            put("attSchoolPeriodId","1")
            put("attSchoolPeriodTitle","")
            put("attSessionStartDateTime",dataStartTime )
            put("attSessionEndDateTime",dataEndTime)
            put("attCapturingIntervalDateTime","")
            put("attCapturingIntervalInSec","")
            put("attCapturingCycleState","")
            put("attCategory","Regular")
            put("studAttComment","")
            put("attSessionStudId","")
            put("attCodeId",att.atteId)
            put("attCodeLngName","present")
            put("attCode",att.status)
            put("studAttStartDateTime",dataStartTime)
            put("studAttEndDateTime",dataEndTime)
            put("studAttTotalDuration","")
            put("atsaId","")
            put("atsaIsProxy","")
            put("atsaDistanceDeltaInMeter","")
            put("isSelfUsrAttMarked","")
            put("attCoLectureCpIds","")
            put("toRemoveCoLecturerCpIds","")
            put("toAddCoLecturerCpIds","")
            put("status","A")

        }

    }

    private fun showPopupWithOk(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                // ✅ Go back to AttendanceActivity
                val intent = Intent(this@AttendanceOverviewActivity, AttendanceActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
            .show()
    }

}

data class ClassOverviewData(
    val className: String,
    val totalStudents: Int,
    val presentCount: Int,
    val absentCount: Int,
  //  val presentStudents: List<String>,
)
