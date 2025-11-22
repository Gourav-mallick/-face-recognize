package com.example.login.view

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
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
import androidx.core.widget.addTextChangedListener


class FaceRegistrationActivity : AppCompatActivity() {

    private lateinit var radioUserType: RadioGroup
    private lateinit var radioActionType: RadioGroup

    private lateinit var editSearchId: EditText

    private lateinit var editName: EditText
    private lateinit var btnEnrollFace: Button
    private var selectedStudent: Student? = null
    private var selectedTeacher: Teacher? = null
    private val DIST_THRESHOLD = 0.80f

    private lateinit var listUsers: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private var allStudents = listOf<Student>()
    private var allTeachers = listOf<Teacher>()
    private var filteredNames = mutableListOf<String>()


    private val liveCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == RESULT_OK) {


                // 🔹 Get all 3 images from camera

                val img1 = result.data?.getByteArrayExtra("face_img_1")
                val img2 = result.data?.getByteArrayExtra("face_img_2")
                val img3 = result.data?.getByteArrayExtra("face_img_3")

                if (img1 == null || img2 == null || img3 == null) {
                    Toast.makeText(this, "Capture failed (missing images)", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }

                // -----------------------------
                // 🔹 Convert to Bitmaps
                // -----------------------------
                val bmp1 = BitmapFactory.decodeByteArray(img1, 0, img1.size)
                val bmp2 = BitmapFactory.decodeByteArray(img2, 0, img2.size)
                val bmp3 = BitmapFactory.decodeByteArray(img3, 0, img3.size)



                //  ADD THIS MIRRORING (Same as Teacher & Student scan)
                fun mirrorBitmap(src: Bitmap): Bitmap {
                    val m = Matrix().apply { preScale(-1f, 1f) }
                    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                }

                val bmp1m = mirrorBitmap(bmp1)
                val bmp2m = mirrorBitmap(bmp2)
                val bmp3m = mirrorBitmap(bmp3)

                // -----------------------------
                // 🔹 Generate embeddings
                // -----------------------------
                val helper = FaceNetHelper(this)
                val e1 = helper.getFaceEmbedding(bmp1m)
                val e2 = helper.getFaceEmbedding(bmp2m)
                val e3 = helper.getFaceEmbedding(bmp3m)

                // -----------------------------
                // 🔹 AVERAGE embedding (final stored)
                // -----------------------------
                val avgEmbedding = FloatArray(e1.size) { i ->
                    (e1[i] + e2[i] + e3[i]) / 3f
                }

                val id = selectedStudent?.studentId ?: selectedTeacher?.staffId
                if (id == null) {
                    Toast.makeText(this, "User ID missing", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }

                // -----------------------------
                // 🔹 Save final averaged embedding
                // -----------------------------
                saveFace(id, avgEmbedding)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Error during face capture: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("EnrollActivity", "Face capture error", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_registration)

        radioUserType = findViewById(R.id.radioUserType)
        radioActionType = findViewById(R.id.radioActionType)

        editSearchId = findViewById(R.id.editSearchId)
        editName = findViewById(R.id.editName)
        btnEnrollFace = findViewById(R.id.btnEnrollFace)


        listUsers = findViewById(R.id.listUsers)


        setupSearchDropdown()
        loadLocalUsers()
        setupDropdownListeners()

        btnEnrollFace.setOnClickListener { handleActionClick() }
    }


    private fun loadLocalUsers() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@FaceRegistrationActivity)
            allStudents = db.studentsDao().getAllStudents()
            allTeachers = db.teachersDao().getAllTeachers()
        }
    }


    private fun setupSearchDropdown() {

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filteredNames)
        listUsers.adapter = adapter

        editSearchId.addTextChangedListener {

            val query = it.toString().trim().lowercase()

            if (query.isEmpty()) {
                //  Clear selection
                editName.setText("")
                selectedStudent = null
                selectedTeacher = null

                //  Clear dropdown
                hideDropdown()
                filteredNames.clear()
                adapter.notifyDataSetChanged()
                return@addTextChangedListener
            }

            filteredNames.clear()

            val type = selectedUserType()
            filteredNames.addAll(
                if (type == "student") {

                    allStudents.filter { s ->
                        s.studentName.lowercase().contains(query) ||
                                s.studentId.lowercase().contains(query)
                    }.map { "${it.studentName} (${it.studentId})" }

                } else {

                    allTeachers.filter { t ->
                        t.staffName.lowercase().contains(query) ||
                                t.staffId.lowercase().contains(query)
                    }.map { "${it.staffName} (${it.staffId})" }
                }
            )


            listUsers.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }


    private fun setupDropdownListeners() {

        listUsers.setOnItemClickListener { _, _, position, _ ->
            val selected = filteredNames[position]
            val name = selected.substringBefore("(").trim()
            val id = selected.substringAfter("(").removeSuffix(")").trim()

            // editSearchId.setText("$name ($id)")
            editName.setText("$name ($id)")


            val type = selectedUserType()
            if (type == "student") {
                selectedStudent = allStudents.find { it.studentId == id }
                selectedTeacher = null
            } else {
                selectedTeacher = allTeachers.find { it.staffId == id }
                selectedStudent = null
            }

            hideDropdown()

            //  NEW: Hide keyboard
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(editSearchId.windowToken, 0)

            //  NEW: Remove focus from EditText so keyboard does NOT reopen
            editSearchId.clearFocus()
            editName.clearFocus()
        }


        // Hide list when clicking outside
        findViewById<FrameLayout>(R.id.rootLayout).setOnClickListener {
            hideDropdown()
        }
    }

    private fun hideDropdown() {
        listUsers.visibility = View.GONE
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
                if (id != null) showDeleteFaceRegisterAuthDialog(id)
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
                    Toast.makeText(this@FaceRegistrationActivity, "No active Internet!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Step 3: Show progress while syncing
                val dialog = android.app.AlertDialog.Builder(this@FaceRegistrationActivity)
                    .setTitle("Preparing for Registration")
                    .setMessage("Syncing with server, please wait...")
                    .setCancelable(false)
                    .create()
                dialog.show()

                // Step 4: Run pre-sync (wait until local DB updated)
                val synced = withContext(Dispatchers.IO) { runPreSyncBeforeEnrollment() }
                dialog.dismiss()

                if (!synced) {
                    Toast.makeText(
                        this@FaceRegistrationActivity,
                        "Sync failed or timed out. Try again later.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // Step 5: Proceed with face capture only if sync completed
                val intent = Intent(this@FaceRegistrationActivity, CameraCaptureActivity::class.java)

                val name = selectedStudent?.studentName ?: selectedTeacher?.staffName ?: ""
                val id = selectedStudent?.studentId ?: selectedTeacher?.staffId ?: ""

                intent.putExtra("user_name", name)
                intent.putExtra("user_id", id)
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
                if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this@FaceRegistrationActivity)) {
                    showMainToast("No network connection!")
                    return@launch
                }
                if (!CheckNetworkAndInternetUtils.hasInternetAccess()) {
                    showMainToast("No internet access!")
                    return@launch
                }

                val db = AppDatabase.getDatabase(this@FaceRegistrationActivity)
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
                            showMainToast("Registered User belongs to $type $name")
                            return@launch
                        }
                        if (action == "add") {
                            showMainToast("This user already Registered..")
                            return@launch
                        }
                    }
                }

                when (action) {
                    "add" -> {
                        if (!existingEmbedding.isNullOrEmpty()) {
                            showMainToast("Face already exists.")
                            return@launch
                        }
                        if (embedding == null) return@launch
                        sendFaceToServer(id, userType, embedStr)
                    }

                    "update" -> {
                        if (existingEmbedding.isNullOrEmpty()) {
                            showMainToast("No existing face found.")
                            return@launch
                        }
                        if (embedding == null) return@launch
                        val storedEmbedding = existingEmbedding.split(",").map { it.toFloat() }.toFloatArray()
                        val distSelf = FaceNetHelper(this@FaceRegistrationActivity)
                            .calculateDistance(storedEmbedding, embedding)
                        if (distSelf >= DIST_THRESHOLD) {
                            showMainToast("Face does not match the existing face.")
                            return@launch
                        }
                        sendFaceToServer(id, userType, embedStr)
                    }

                    "delete" -> {
                        if (existingEmbedding.isNullOrEmpty()) {
                            showMainToast("No face registered for this user.")
                            return@launch
                        }

                        if (userType == "student") {
                            db.studentsDao().updateStudentEmbedding(id, "")
                        } else {
                            db.teachersDao().updateTeacherEmbedding(id, "")
                        }

                        showMainToast("Face deleted successfully.")
                    }

                }

                val updated =
                    if (userType == "student") db.studentsDao().getStudentById(id)
                    else db.teachersDao().getTeacherById(id)

                Log.d("EnrollActivity", "Updated Record → $updated")
                showMainToast("User Registration $action successfully.")
            } catch (e: Exception) {
                Log.e("EnrollActivity", "saveFace error", e)
                showMainToast(" some error occurred, please try again")
            }
        }
    }

    private suspend fun showMainToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@FaceRegistrationActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun detectMatchingFace(
        newEmbedding: FloatArray,
        threshold: Float = DIST_THRESHOLD
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
                .getString("baseUrl", "")!!
            val hash = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                .getString("hash", null)
            val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)
            val response = api.postUserRegistration(body = requestBody)
            Log.d("EnrollActivity", "Response: $response")

            withContext(Dispatchers.Main) {
                if (!response.isSuccessful) {
                    Toast.makeText(this@FaceRegistrationActivity, "HTTP error: ${response.code()}", Toast.LENGTH_LONG).show()
                    return@withContext
                }

                val bodyStr = response.body()?.string() ?: ""
                Log.d("EnrollActivity", "Response body: $bodyStr")

                val jsonObj = JSONObject(bodyStr)
                val collection = jsonObj.optJSONObject("collection")
                val resp = collection?.optJSONObject("response")
                val successStatus = resp?.optString("successStatus", "FALSE") ?: "FALSE"

                if (successStatus.equals("TRUE", ignoreCase = true)) {
                    Toast.makeText(this@FaceRegistrationActivity, "Face synced to server!", Toast.LENGTH_LONG).show()

                    // 🔹 Trigger local DB sync automatically
                    val workRequest = OneTimeWorkRequestBuilder<AutoSyncWorker>()
                        .setInitialDelay(3, TimeUnit.SECONDS) // Optional delay
                        .build()

                    WorkManager.getInstance(this@FaceRegistrationActivity).enqueue(workRequest)
                } else {
                    Toast.makeText(this@FaceRegistrationActivity, "Server rejected data!", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FaceRegistrationActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            Log.e("EnrollActivity", "sendFaceToServer error", e)
        }
    }


    private fun showDeleteFaceRegisterAuthDialog(userId: String) {
        val input = EditText(this)
        input.hint = "Enter admin PIN"
        input.setPadding(40, 40, 40, 40)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Authentication Required")
            .setMessage("Enter admin PIN to delete face data")
            .setView(input)
            .setCancelable(true)
            .setPositiveButton("Confirm") { _, _ ->
                val pin = input.text.toString().trim()

                // 🔐 Change this PIN as you want
                if (pin == "1234") {
                    // Auth Passed → delete face
                    saveFace(userId, null)
                } else {
                    Toast.makeText(this, "Invalid PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
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
                WorkManager.getInstance(this@FaceRegistrationActivity)
                    .enqueueUniqueWork(
                        "PreEnrollSync",
                        ExistingWorkPolicy.KEEP,
                        workRequest
                    )

                val workId = workRequest.id
                var waited = 0
                while (waited < 30000) { // 30 sec timeout
                    val info = WorkManager.getInstance(this@FaceRegistrationActivity)
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
