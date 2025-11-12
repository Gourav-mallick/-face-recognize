package com.example.login.view

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Student
import com.example.login.db.entity.Teacher
import com.example.login.utility.CheckNetworkAndInternetUtils
import com.example.login.utility.FaceNetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.login.utility.AutoSyncWorker
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import kotlinx.coroutines.delay


class EnrollActivity : AppCompatActivity() {

    private lateinit var radioUserType: RadioGroup
    private lateinit var radioActionType: RadioGroup

    private lateinit var editSearchId: EditText
    private lateinit var btnSearch: Button

    private lateinit var editName: EditText
    private lateinit var btnEnrollFace: Button

    private var selectedStudent: Student? = null
    private var selectedTeacher: Teacher? = null

    private val DIST_THRESHOLD = 0.80f

    private val liveCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK) {
                val bytes = result.data?.getByteArrayExtra("face_bytes")
                if (bytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val embedding = FaceNetHelper(this).getFaceEmbedding(bmp)
                    val id = selectedStudent?.studentId ?: selectedTeacher?.staffId
                    if (id != null) saveFace(id, embedding)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error during face capture: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("EnrollActivity", "Face capture error", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enroll)

        radioUserType = findViewById(R.id.radioUserType)
        radioActionType = findViewById(R.id.radioActionType)

        editSearchId = findViewById(R.id.editSearchId)
        btnSearch = findViewById(R.id.btnSearch)

        editName = findViewById(R.id.editName)
        btnEnrollFace = findViewById(R.id.btnEnrollFace)

        btnSearch.setOnClickListener { searchUser() }
        btnEnrollFace.setOnClickListener { handleActionClick() }
    }

    private fun searchUser() {
        try {
            val enteredId = editSearchId.text.toString().trim()
            if (enteredId.isEmpty()) {
                Toast.makeText(this, "Enter an ID to search!", Toast.LENGTH_SHORT).show()
                return
            }

            val userType = selectedUserType()
            when (userType) {
                "student" -> searchStudentById(enteredId)
                "staff" -> searchTeacherById(enteredId)
                else -> Toast.makeText(this, "Select user type first!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("EnrollActivity", "searchUser error", e)
        }
    }

    private fun selectedUserType(): String {
        return when (radioUserType.checkedRadioButtonId) {
            R.id.rbStudent -> "student"
            R.id.rbStaff -> "staff"
            else -> "none"
        }
    }

    private fun selectedActionType(): String {
        return when (radioActionType.checkedRadioButtonId) {
            R.id.rbAdd -> "add"
            R.id.rbUpdate -> "update"
            R.id.rbDelete -> "delete"
            else -> "none"
        }
    }

    private fun searchStudentById(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@EnrollActivity)
                val student = db.studentsDao().getStudentById(id)

                withContext(Dispatchers.Main) {
                    if (student == null) {
                        Toast.makeText(this@EnrollActivity, "Student not found!", Toast.LENGTH_SHORT).show()
                        selectedStudent = null
                        editName.setText("")
                    } else {
                        selectedStudent = student
                        selectedTeacher = null
                        editName.setText("${student.studentId} - ${student.studentName}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EnrollActivity, "Error searching student: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("EnrollActivity", "searchStudentById error", e)
            }
        }
    }

    private fun searchTeacherById(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@EnrollActivity)
                val teacher = db.teachersDao().getTeacherById(id)

                withContext(Dispatchers.Main) {
                    if (teacher == null) {
                        Toast.makeText(this@EnrollActivity, "Teacher not found!", Toast.LENGTH_SHORT).show()
                        selectedTeacher = null
                        editName.setText("")
                    } else {
                        selectedTeacher = teacher
                        selectedStudent = null
                        editName.setText("${teacher.staffId} - ${teacher.staffName}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EnrollActivity, "Error searching teacher: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("EnrollActivity", "searchTeacherById error", e)
            }
        }
    }

    private fun handleActionClick() {
        try {
            val userFound = selectedStudent != null || selectedTeacher != null
            if (!userFound) {
                Toast.makeText(this, "Search user first!", Toast.LENGTH_SHORT).show()
                return
            }

            val action = selectedActionType()

            if (action == "delete") {
                val id = selectedStudent?.studentId ?: selectedTeacher?.staffId
                if (id != null) saveFace(id, null)
                return
            }

            // Step 1: Check basic network
            if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this)) {
                Toast.makeText(this, "No network connection!", Toast.LENGTH_SHORT).show()
                return
            }

            lifecycleScope.launch(Dispatchers.Main) {
                // Step 2: Check internet
                val hasInternet = withContext(Dispatchers.IO) { CheckNetworkAndInternetUtils.hasInternetAccess() }
                if (!hasInternet) {
                    Toast.makeText(this@EnrollActivity, "No active Internet!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Step 3: Show progress while syncing
                val dialog = android.app.AlertDialog.Builder(this@EnrollActivity)
                    .setTitle("Preparing for Enrollment")
                    .setMessage("Syncing with server, please wait...")
                    .setCancelable(false)
                    .create()
                dialog.show()

                // Step 4: Run pre-sync (wait until local DB updated)
                val synced = withContext(Dispatchers.IO) { runPreSyncBeforeEnrollment() }
                dialog.dismiss()

                if (!synced) {
                    Toast.makeText(
                        this@EnrollActivity,
                        "Sync failed or timed out. Try again later.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // Step 5: Proceed with face capture only if sync completed
                val intent = Intent(this@EnrollActivity, CameraCaptureActivity::class.java)
                liveCaptureLauncher.launch(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("EnrollActivity", "handleActionClick error", e)
        }
    }

    private fun saveFace(id: String, embedding: FloatArray?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // check connection before server call
                if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this@EnrollActivity)) {
                    showMainToast("No network connection!")
                    return@launch
                }
                if (!CheckNetworkAndInternetUtils.hasInternetAccess()) {
                    showMainToast("No internet access!")
                    return@launch
                }

                val db = AppDatabase.getDatabase(this@EnrollActivity)
                val embedStr = embedding?.joinToString(",")
                val userType = selectedUserType()
                val action = selectedActionType()

                val student = if (userType == "student") db.studentsDao().getStudentById(id) else null
                val teacher = if (userType == "staff") db.teachersDao().getTeacherById(id) else null

                val existingEmbedding = student?.embedding ?: teacher?.embedding

                if (embedding != null && (action == "add" || action == "update")) {
                    val match = detectMatchingFace(embedding)
                    if (match != null) {
                        val (type, name, matchedId) = match
                        if (matchedId != id) {
                            showMainToast("Enroll already belongs to $type $name")
                            return@launch
                        }
                        if (action == "add") {
                            showMainToast("This user already has an Enroll. Use Update instead.")
                            return@launch
                        }
                    }
                }

                when (action) {
                    "add" -> {
                        if (!existingEmbedding.isNullOrEmpty()) {
                            showMainToast("Face already exists. Use Update instead.")
                            return@launch
                        }
                        if (embedding == null) return@launch
                        sendFaceToServer(id, userType, embedStr)
                    }

                    "update" -> {
                        if (existingEmbedding.isNullOrEmpty()) {
                            showMainToast("No existing face found. Use Add instead.")
                            return@launch
                        }
                        if (embedding == null) return@launch
                        val storedEmbedding = existingEmbedding.split(",").map { it.toFloat() }.toFloatArray()
                        val distSelf = FaceNetHelper(this@EnrollActivity)
                            .calculateDistance(storedEmbedding, embedding)
                        if (distSelf >= DIST_THRESHOLD) {
                            showMainToast("Face does not match the existing face.")
                            return@launch
                        }
                        sendFaceToServer(id, userType, embedStr)
                    }

                    "delete" -> {
                        showMainToast("Delete logic placeholder.")
                    }
                }

                val updated =
                    if (userType == "student") db.studentsDao().getStudentById(id)
                    else db.teachersDao().getTeacherById(id)

                Log.d("EnrollActivity", "Updated Record → $updated")
                showMainToast("Action $action completed successfully.")
            } catch (e: Exception) {
                Log.e("EnrollActivity", "saveFace error", e)
                showMainToast("Error: ${e.message}")
            }
        }
    }

    private suspend fun showMainToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@EnrollActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun detectMatchingFace(
        newEmbedding: FloatArray,
        threshold: Float = 0.80f
    ): Triple<String, String, String>? {
        return try {
            val db = AppDatabase.getDatabase(this)
            val faceNet = FaceNetHelper(this)

            var minDist = Float.MAX_VALUE
            var matchType = ""
            var matchName = ""
            var matchId = ""

            val students = db.studentsDao().getAllStudents().filter { !it.embedding.isNullOrEmpty() }
            for (s in students) {
                val stored = s.embedding!!.split(",").map { it.toFloat() }.toFloatArray()
                val dist = faceNet.calculateDistance(stored, newEmbedding)
                if (dist < minDist) {
                    minDist = dist
                    matchType = "student"
                    matchName = s.studentName
                    matchId = s.studentId
                }
            }

            val teachers = db.teachersDao().getAllTeachers().filter { !it.embedding.isNullOrEmpty() }
            for (t in teachers) {
                val stored = t.embedding!!.split(",").map { it.toFloat() }.toFloatArray()
                val dist = faceNet.calculateDistance(stored, newEmbedding)
                if (dist < minDist) {
                    minDist = dist
                    matchType = "teacher"
                    matchName = t.staffName
                    matchId = t.staffId
                }
            }
            if (minDist < threshold) Triple(matchType, matchName, matchId) else null
        } catch (e: Exception) {
            Log.e("EnrollActivity", "detectMatchingFace error", e)
            null
        }
    }

    private suspend fun sendFaceToServer(
        id: String,
        userType: String,
        embeddingStr: String?
    ) {
        val json = """
        {
          "userRegParamData": {
            "userType": "$userType",
            "registrationType": "FingerPrint",
            "regParamData": [
              {
                "userId": "$id",
                "metricType": "finger",
                "fingerType": "Biometric",
                "template": "${embeddingStr ?: ""}"
              }
            ]
          }
        }
        """.trimIndent()

        try {
            val mediaType = MediaType.parse("application/json; charset=utf-8")
            val requestBody = RequestBody.create(mediaType, json)
            val baseUrl = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                .getString("baseUrl", "https://testvps.digitaledu.in/")!!
            val hash = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                .getString("hash", null)
            val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)
            val response = api.postUserRegistration(body = requestBody)
            Log.d("EnrollActivity", "Response: $response")

            withContext(Dispatchers.Main) {
                if (!response.isSuccessful) {
                    Toast.makeText(this@EnrollActivity, "HTTP error: ${response.code()}", Toast.LENGTH_LONG).show()
                    return@withContext
                }

                val bodyStr = response.body()?.string() ?: ""
                Log.d("EnrollActivity", "Response body: $bodyStr")

                val jsonObj = JSONObject(bodyStr)
                val collection = jsonObj.optJSONObject("collection")
                val resp = collection?.optJSONObject("response")
                val successStatus = resp?.optString("successStatus", "FALSE") ?: "FALSE"

                if (successStatus.equals("TRUE", ignoreCase = true)) {
                    Toast.makeText(this@EnrollActivity, "Face synced to server!", Toast.LENGTH_LONG).show()

                    // 🔹 Trigger local DB sync automatically
                    val workRequest = OneTimeWorkRequestBuilder<AutoSyncWorker>()
                        .setInitialDelay(3, TimeUnit.SECONDS) // Optional delay
                        .build()

                    WorkManager.getInstance(this@EnrollActivity).enqueue(workRequest)
                } else {
                    Toast.makeText(this@EnrollActivity, "Server rejected data!", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@EnrollActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            Log.e("EnrollActivity", "sendFaceToServer error", e)
        }
    }


    private suspend fun runPreSyncBeforeEnrollment(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<AutoSyncWorker>()
                    .setConstraints(constraints)
                    .build()

                // Run unique sync job so we don't start duplicates
                WorkManager.getInstance(this@EnrollActivity)
                    .enqueueUniqueWork(
                        "PreEnrollSync",
                        ExistingWorkPolicy.KEEP,
                        workRequest
                    )

                val workId = workRequest.id
                var waited = 0
                while (waited < 30000) { // 30 sec timeout
                    val info = WorkManager.getInstance(this@EnrollActivity)
                        .getWorkInfoById(workId).get()
                    when (info?.state) {
                        WorkInfo.State.SUCCEEDED -> return@withContext true
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> return@withContext false
                        else -> {
                            delay(500)
                            waited += 500
                        }
                    }
                }
                false // timeout
            } catch (e: Exception) {
                Log.e("EnrollActivity", "PreSync error: ${e.message}")
                false
            }
        }
    }

}
