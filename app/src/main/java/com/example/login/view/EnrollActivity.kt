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
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Student
import com.example.login.db.entity.Teacher
import com.example.login.utility.FaceNetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        if (result.resultCode == RESULT_OK) {
            val bytes = result.data?.getByteArrayExtra("face_bytes")
            if (bytes != null) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val embedding = FaceNetHelper(this).getFaceEmbedding(bmp)

                val id = selectedStudent?.studentId ?: selectedTeacher?.staffId
                saveFace(id!!, embedding)
            }
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

    // --------------------------------------------------------
    // SEARCH USER (Student / Teacher)
    // --------------------------------------------------------
    private fun searchUser() {
        val enteredId = editSearchId.text.toString().trim()
        if (enteredId.isEmpty()) {
            Toast.makeText(this, "Enter an ID to search!", Toast.LENGTH_SHORT).show()
            return
        }

        val userType = selectedUserType()
        when (userType) {
            "student" -> searchStudentById(enteredId)
            "teacher" -> searchTeacherById(enteredId)
            else -> Toast.makeText(this, "Select user type first!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectedUserType(): String {
        return when (radioUserType.checkedRadioButtonId) {
            R.id.rbStudent -> "student"
            R.id.rbTeacher -> "teacher"
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
        }
    }

    private fun searchTeacherById(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
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
        }
    }

    // --------------------------------------------------------
    // ACTION BUTTON CLICK (Add / Update / Delete)
    // --------------------------------------------------------
    private fun handleActionClick() {
        val userFound = selectedStudent != null || selectedTeacher != null
        if (!userFound) {
            Toast.makeText(this, "Search user first!", Toast.LENGTH_SHORT).show()
            return
        }

        val action = selectedActionType()

        // DELETE does not need camera
        if (action == "delete") {
            val id = selectedStudent?.studentId ?: selectedTeacher?.staffId
            saveFace(id!!, null)
            return
        }

        // ADD or UPDATE capture new face
        val intent = Intent(this, CameraCaptureActivity::class.java)
        liveCaptureLauncher.launch(intent)
    }

    // --------------------------------------------------------
    // SAVE FACE (Add / Update / Delete)
    // --------------------------------------------------------
    private fun saveFace(id: String, embedding: FloatArray?) {
        val db = AppDatabase.getDatabase(this)
        val embedStr = embedding?.joinToString(",")

        val userType = selectedUserType()
        val action = selectedActionType()

        lifecycleScope.launch(Dispatchers.IO) {

            val student = if (userType == "student") db.studentsDao().getStudentById(id) else null
            val teacher = if (userType == "teacher") db.teachersDao().getTeacherById(id) else null

            val existingEmbedding = student?.embedding ?: teacher?.embedding


            // --------------------------------------------------------
            // ✅ 1. Face duplicate detection (Euclidean distance)
            // --------------------------------------------------------
            if (embedding != null && (action == "add" || action == "update")) {

                val match = detectMatchingFace(embedding)

                if (match != null) {
                    val (type, name, matchedId) = match

                    // ✅ CASE 1: Face belongs to someone else → BLOCK
                    if (matchedId != id) {
                        showMainToast("Face already belongs to $type $name")
                        return@launch
                    }

                    // ✅ CASE 2: Face belongs to SAME user → allowed for UPDATE
                    if (action == "add") {
                        showMainToast("This user already has a face. Use Update instead.")
                        return@launch
                    }
                }
            }



            // --------------------------------------------------------
            // ✅ 2. Add / Update / Delete logic
            // --------------------------------------------------------
            when (action) {

                // ADD
                "add" -> {
                    if (!existingEmbedding.isNullOrEmpty()) {
                        showMainToast("Face already exists. Use Update instead.")
                        return@launch
                    }
                    if (embedding == null) return@launch

                    if (userType == "student")
                        db.studentsDao().updateStudentEmbedding(id, embedStr!!)
                    else
                        db.teachersDao().updateTeacherEmbedding(id, embedStr!!)
                }

                // ✅ SECURE UPDATE: only same person can update
                "update" -> {

                    if (existingEmbedding.isNullOrEmpty()) {
                        showMainToast("No existing face found. Use Add instead.")
                        return@launch
                    }
                    if (embedding == null) return@launch

                    // 1. Compare captured face with the user's own stored face
                    val storedEmbedding = existingEmbedding.split(",").map { it.toFloat() }.toFloatArray()
                    val distSelf = FaceNetHelper(this@EnrollActivity).calculateDistance(storedEmbedding, embedding)

                    // If not same person → block update
                    if (distSelf >= DIST_THRESHOLD) {
                        showMainToast("Face does not match the user's existing face. Update blocked.")
                        return@launch
                    }

                    // 2. Safe: update with new embedding
                    if (userType == "student")
                        db.studentsDao().updateStudentEmbedding(id, embedStr!!)
                    else
                        db.teachersDao().updateTeacherEmbedding(id, embedStr!!)
                }


                // DELETE
                "delete" -> {
                    if (existingEmbedding.isNullOrEmpty()) {
                        showMainToast("No embedding to delete.")
                        return@launch
                    }

                    if (userType == "student")
                        db.studentsDao().updateStudentEmbedding(id, "")
                    else
                        db.teachersDao().updateTeacherEmbedding(id, "")
                }
            }


            // --------------------------------------------------------
            // ✅ 3. Log updated record
            // --------------------------------------------------------
            val updated =
                if (userType == "student") db.studentsDao().getStudentById(id)
                else db.teachersDao().getTeacherById(id)

            Log.d("EnrollActivity", "Updated Record → $updated")

            showMainToast("Action $action completed successfully.")
        }
    }

    private suspend fun showMainToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@EnrollActivity, msg, Toast.LENGTH_LONG).show()
        }
    }


    private suspend fun detectMatchingFace(
        newEmbedding: FloatArray,
        threshold: Float = 0.80f
    ): Triple<String, String, String>? {

        val db = AppDatabase.getDatabase(this)
        val faceNet = FaceNetHelper(this)

        var minDist = Float.MAX_VALUE
        var matchType = ""
        var matchName = ""
        var matchId = ""

        // Check Students
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

        // Check Teachers
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

        return if (minDist < threshold) Triple(matchType, matchName, matchId) else null
    }


}
