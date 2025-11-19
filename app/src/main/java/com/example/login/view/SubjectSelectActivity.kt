package com.example.login.view


import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.login.R
import com.example.login.databinding.ActivityPeriodCourseSelectBinding
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.launch



class SubjectSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityPeriodCourseSelectBinding
    private lateinit var db: AppDatabase
    private lateinit var sessionId: String
    private lateinit var selectedClasses: List<String>

    private val selectedCourseIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriodCourseSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        sessionId = intent.getStringExtra("SESSION_ID") ?: return
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()


        // 🔹 Disable back press (both button and gesture)
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@SubjectSelectActivity,
                    "Back disabled on this screen",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // 🔹 Save app state so that reopening resumes here
        getSharedPreferences("APP_STATE", MODE_PRIVATE)
            .edit()
            .putBoolean("IS_IN_PERIOD_SELECT", true)
            .putString("SESSION_ID", sessionId)
            .apply()

       // setupPeriodDropdown()
        loadCourses()

        binding.btnContinue.setOnClickListener {
            handleContinue()
        }

    }



    // 🔹 Load courses from DB
    private fun loadCourses() {
        lifecycleScope.launch {
            val teacherId = db.sessionDao().getSessionById(sessionId)?.teacherId ?: ""

            // 1) Find cpIds assigned to this teacher
            val assignedCoursePeriods = db.coursePeriodDao().getAllCoursePeriods()
                .filter { it.teacherId == teacherId }

            val teacherCourseIds = assignedCoursePeriods.map { it.courseId }.toSet()

            val courses = if (teacherCourseIds.isNotEmpty()) {
                // only load courses which belong to teacher
                db.courseDao().getAllCourses().filter { course ->
                    teacherCourseIds.contains(course.courseId)
                }
            } else {
                db.courseDao().getAllCourses()
            }


            Log.d("CourseSelectActivity", "Courses: $courses")


            val adapter = SubjectSelectAdapter(courses) { selectedIds ->
                selectedCourseIds.clear()
                selectedCourseIds.addAll(selectedIds)
            }

            binding.recyclerViewCourses.layoutManager = LinearLayoutManager(this@SubjectSelectActivity)
            binding.recyclerViewCourses.adapter = adapter
        }
    }


    // 🔹 Handle "Continue" button click
    private fun handleContinue() {

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@SubjectSelectActivity)
            val isMultiClass = selectedClasses.size > 1


            // 1) Get selected CPIDs from selected courses
            val selectedCourseInfo = db.courseDao().getCourseDetailsForIds(selectedCourseIds)
            val selectedCpIds = selectedCourseInfo
                .mapNotNull { it.cpId }
                .toSet()


            // 2) Get only present students for this session (FIX: mapNotNull)
            val sessionAttendances = db.attendanceDao().getAttendancesForSession(sessionId)
            val studentsInSession = sessionAttendances.mapNotNull { att ->
                db.studentsDao().getStudentById(att.studentId)
            }

           // 3) Load all student schedules
            val schedules = db.studentScheduleDao().getAll()

          // 4) Correct match logic
            val notEnrolledStudents = studentsInSession.filter { student ->

                val studentCpIds = schedules
                    .filter { it.studentId == student.studentId }
                    .map { it.cpId }
                    .toSet()
                // Student is NOT enrolled if intersection is empty
                (studentCpIds.intersect(selectedCpIds)).isEmpty()
            }


            //  5) Show popup BUT do not stop flow

            if (notEnrolledStudents.isNotEmpty()) {

                val details = notEnrolledStudents.joinToString("\n") { s ->
                    "${s.studentName} - ${s.studentId}"
                }

                val count = notEnrolledStudents.size

                val inflater = LayoutInflater.from(this@SubjectSelectActivity)
                val view = inflater.inflate(R.layout.dialog_scroll_list, null)
                view.findViewById<TextView>(R.id.tvDetails).text = details

                AlertDialog.Builder(this@SubjectSelectActivity)
                    .setTitle("Students Not Schedule ($count)")
                    .setView(view)
                    .setPositiveButton("OK") { _, _ ->
                        continueAndSaveSelectedCourse()     // CONTINUE AFTER CLICK
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()                 // STOP - do NOTHING
                    }
                    .setCancelable(false)
                    .show()

                return@launch   // STOP UNTIL OK
            }


            //  NOT ENROLLED = ZERO → flow continues automatically
            continueAndSaveSelectedCourse()

        }


    }


    private fun continueAndSaveSelectedCourse() {

        lifecycleScope.launch {

            val db = AppDatabase.getDatabase(this@SubjectSelectActivity)
            val isMultiCourse = selectedCourseIds.size > 1
            val isNoCourse = selectedCourseIds.isEmpty()

            try {

                when {
                    isNoCourse -> {
                        Toast.makeText(
                            this@SubjectSelectActivity,
                            "Please select or add a course",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    isMultiCourse -> {
                        val courseDetails = db.courseDao().getCourseDetailsForIds(selectedCourseIds)

                        if (courseDetails.isEmpty()) {
                            Toast.makeText(this@SubjectSelectActivity, "No course details found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        val combinedCpIds = courseDetails.mapNotNull { it.cpId }.joinToString(",")
                        val combinedCourseIds = courseDetails.mapNotNull { it.courseId }.joinToString(",")
                        val combinedCourseTitles = courseDetails.mapNotNull { it.courseTitle }.joinToString(",")
                        val combinedCourseShortNames = courseDetails.mapNotNull { it.courseShortName }.joinToString(",")
                        val combinedSubjectIds = courseDetails.mapNotNull { it.subjectId }.joinToString(",")
                        val combinedSubjectTitles = courseDetails.mapNotNull { it.subjectTitle }.joinToString(",")
                        val combinedClassShortNames = courseDetails.mapNotNull { it.classShortName }.distinct().joinToString(",")
                        val combinedMpIds = courseDetails.mapNotNull { it.mpId }.distinct().joinToString(",")
                        val combinedMpLongTitles = courseDetails.mapNotNull { it.mpLongTitle }.distinct().joinToString(",")

                        for (classId in selectedClasses) {
                            db.attendanceDao().updateAttendanceWithCourseDetails(
                                sessionId = sessionId,
                                cpId = combinedCpIds,
                                courseId = combinedCourseIds,
                                courseTitle = combinedCourseTitles,
                                subjectId = combinedSubjectIds,
                                courseShortName = combinedCourseShortNames,
                                subjectTitle = combinedSubjectTitles,
                                classShortName = combinedClassShortNames,
                                mpId = combinedMpIds,
                                mpLongTitle = combinedMpLongTitles
                            )
                        }

                        db.sessionDao().updateSessionPeriodAndSubject(sessionId, combinedCourseIds)

                        val intent = Intent(this@SubjectSelectActivity, AttendanceOverviewActivity::class.java)
                        intent.putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
                        intent.putExtra("SESSION_ID", sessionId)
                        startActivity(intent)

                        kotlinx.coroutines.delay(500)
                        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()
                        finish()
                    }

                    else -> {
                        val courseId = selectedCourseIds.first()
                        val courseDetails = db.courseDao().getCourseDetailsForIds(listOf(courseId)).firstOrNull()

                        if (courseDetails == null) {
                            Toast.makeText(this@SubjectSelectActivity, "Course details not found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        for (classId in selectedClasses) {
                            db.attendanceDao().updateAttendanceWithCourseDetails(
                                sessionId = sessionId,
                                cpId = courseDetails.cpId,
                                courseId = courseDetails.courseId,
                                courseTitle = courseDetails.courseTitle,
                                subjectId = courseDetails.subjectId,
                                courseShortName = courseDetails.courseShortName,
                                subjectTitle = courseDetails.subjectTitle,
                                classShortName = courseDetails.classShortName,
                                mpId = courseDetails.mpId,
                                mpLongTitle = courseDetails.mpLongTitle
                            )
                        }

                        db.sessionDao().updateSessionPeriodAndSubject(sessionId, courseId)

                        val intent = Intent(this@SubjectSelectActivity, AttendanceOverviewActivity::class.java)
                        intent.putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
                        intent.putExtra("SESSION_ID", sessionId)
                        startActivity(intent)

                        kotlinx.coroutines.delay(500)
                        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()
                        finish()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }



}
