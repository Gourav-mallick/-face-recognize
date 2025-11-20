package com.example.login.view


import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.CheckBox
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
import android.widget.LinearLayout
import com.example.login.db.entity.StudentSchedule
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import com.example.login.db.entity.PendingScheduleEntity
import com.example.login.utility.CheckNetworkAndInternetUtils


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
            val notScheduleStudents = studentsInSession.filter { student ->

                val studentCpIds = schedules
                    .filter { it.studentId == student.studentId }
                    .map { it.cpId }
                    .toSet()
                // Student is NOT enrolled if intersection is empty
                (studentCpIds.intersect(selectedCpIds)).isEmpty()
            }


            //  5) Show popup BUT do not stop flow

            if (notScheduleStudents.isNotEmpty()) {
            // NEW CHECKBOX POPUP
                val inflater = LayoutInflater.from(this@SubjectSelectActivity)
                val view = inflater.inflate(R.layout.dialog_student_not_schedule_checkbox_list, null)
                val container = view.findViewById<LinearLayout>(R.id.containerStudents)

            // Temp selection set
                val tempSelected = mutableSetOf<String>()
                notScheduleStudents.forEach { s ->
                    tempSelected.add(s.studentId)

                    val cb = CheckBox(this@SubjectSelectActivity)
                    cb.text = "${s.studentName} (${s.studentId})"
                    cb.isChecked = true

                    cb.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) tempSelected.add(s.studentId)
                        else tempSelected.remove(s.studentId)
                    }

                    container.addView(cb)
                }

                AlertDialog.Builder(this@SubjectSelectActivity)
                    .setTitle("Students Not Enrolled (${notScheduleStudents.size})")
                    .setView(view)
                    .setPositiveButton("OK") { _, _ ->
                        lifecycleScope.launch {
                            // REMOVE UNCHECKED STUDENTS FROM ATTENDANCE
                            notScheduleStudents.forEach { s ->
                                if (!tempSelected.contains(s.studentId)) {
                                    Log.d(
                                        "AttendanceLog",
                                        "DELETED → Student ${s.studentId} (${s.studentName}) removed from session $sessionId"
                                    )
                                    db.attendanceDao().deleteAttendanceForStudent(sessionId, s.studentId)
                                }
                            }


                            // 2. NEW → Schedule students (server-first → local sync)
                            scheduleStudentsForSelectedCourses(tempSelected, selectedCourseInfo)



                            // Log - Count how many students are still saved AFTER deletion
                            val remainingAttendance = db.attendanceDao().getAttendancesForSession(sessionId)
                            val remainingCount = remainingAttendance.size
                            Log.d("ATTENDANCE_LOG", "Saved Attendance After Delete: $remainingCount")

                            //add api call if need to scheduling first then save attandance....
                            continueAndSaveSelectedCourse()
                        }
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .show()

                return@launch
             // STOP UNTIL OK
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




    // Add inside SubjectSelectActivity class
    private suspend fun scheduleStudentsForSelectedCourses(
        tempSelected: Set<String>,
        selectedCourseInfo: List<com.example.login.db.entity.CourseFullInfo>
    ) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(this@SubjectSelectActivity)

        // 1) Build actionData array for every selected student × courseInfo pair
        val actionArray = JSONArray()
        val session = db.sessionDao().getSessionById(sessionId)
        val teacherId = session?.teacherId ?: ""
        val teacherName = session?.teacherId?.let { db.teachersDao().getTeacherNameById(it) } ?: "UNKNOWN"
        val todayDate = session?.date ?: java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())

        // if a field is missing from CourseFullInfo (classId etc.) we fallback to safe values
        tempSelected.forEach { studentId ->
            selectedCourseInfo.forEach { info ->
                // skip invalid cp/course rows
                if (info.cpId.isNullOrBlank() || info.courseId.isNullOrBlank()) {
                    Log.d("SCHEDULER", "Skipping invalid info for student=$studentId cpId=${info.cpId} courseId=${info.courseId}")
                    return@forEach
                }

                val student =db.studentsDao().getStudentById(studentId)
                val student_classId=student?.classId

                var Student_className = ""
                if (!student_classId.isNullOrBlank()) {
                    val classTable = db.classDao().getClassById(student_classId)
                    Student_className = classTable?.classShortName ?: ""
                }

                val obj = JSONObject()
                // Keep these fields similar to sample payload you provided.
                obj.put("school_id", "1")
                obj.put("syear", "2024")
                obj.put("marking_period_id", info.mpId ?: "")
                obj.put("mp", info.mpLongTitle ?: "")
                obj.put("class_id", student_classId ?: "")
                obj.put("class_title", Student_className ?: "")
                obj.put("subjectId", info.subjectId ?: "")
                obj.put("headId", "") // keep blank if unknown
                obj.put("course_id", info.courseId ?: "")
                obj.put("course_period_id", info.cpId ?: "")
                obj.put("cp_title", info.courseTitle ?: info.courseShortName ?: "")
                obj.put("teacher_id", teacherId)
                obj.put("teacher_name", teacherName ?: "")
                obj.put("student_id", studentId)

                obj.put("student_name", student?.studentName ?: "")
                obj.put("start_date", todayDate)
                obj.put("created_by", "1")
                obj.put("isCreateScheduling", "Y")
                obj.put("isUpdateScheduling", "N")

                actionArray.put(obj)
            }
        }

        // nothing to send
        if (actionArray.length() == 0) {
            Log.d("SCHEDULER", "No valid student-course pairs to schedule.")
            return@withContext
        }

        val bodyObj = JSONObject().apply {
            put("smParamDataObj", JSONObject().apply {
                put("actionType", "addUpdateStudentSubjectSchedulingTblDetails")
                put("actionData", actionArray)
            })
        }

        val jsonString = bodyObj.toString()
        Log.d("SCHEDULER", "Prepared scheduling payload: $jsonString")

        // 2) Check network
        val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(this@SubjectSelectActivity)
        val hasInternet = if (hasNetwork) CheckNetworkAndInternetUtils.hasInternetAccess() else false

        // 3) Attempt to post to server if internet available
        var serverSuccess = false
        if (hasNetwork && hasInternet) {
            try {
                val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                val baseUrl = prefs.getString("baseUrl", "")!!
                val hash = prefs.getString("hash", "")!!

              //  Log.d("SCHEDULER_API", "Base URL = $baseUrl")
               // Log.e("SCHEDULER_API", "HASH = $hash")
             //   Log.e("SCHEDULER_API", "Posting to endpoint: api/v1/SubjectManager/ManageStudentSubjectScheduling")


                val apiService = ApiClient.getClient(baseUrl, hash)
                    .create(ApiService::class.java)

                val mediaType = okhttp3.MediaType.parse("application/json")
                val requestBody = okhttp3.RequestBody.create(mediaType, jsonString)

                Log.d("SCHEDULER_REQUEST", "JSON Length = ${jsonString.length}")


                val response = apiService.postStudentSubjectSchedule(body = requestBody)

                if (response.isSuccessful && response.body() != null) {
                    val respStr = response.body()!!.string()

                    Log.d("SCHEDULER", "Server response: $respStr")
                    val respJson = JSONObject(respStr)
                    val status = respJson.optJSONObject("collection")
                        ?.optJSONObject("response")
                        ?.optString("status")

                    Log.w("SCHEDULER_RESPONSE", "Raw: $respStr")

                    if (status.equals("SUCCESS", ignoreCase = true)) {
                        serverSuccess = true


                        Log.e("SCHEDULER_SUCCESS", "----- SERVER SUCCESS: STUDENT SCHEDULES SENT -----")
                        tempSelected.forEach { studentId ->
                            selectedCourseInfo.forEach { info ->
                                Log.e(
                                    "SCHEDULER_SUCCESS",
                                    "StudentId=$studentId | cpId=${info.cpId} | courseId=${info.courseId}"
                                )
                            }
                        }
                        Log.e("SCHEDULER_SUCCESS", "--------------------------------------------------")



                    } else {
                        Log.w("SCHEDULER", "Server returned non-success status. Will save locally as pending.")
                        serverSuccess = false
                    }
                } else {
                    val err = response.errorBody()?.string()
                    Log.e("SCHEDULER", "Server call failed: $err")
                    serverSuccess = false
                }

            } catch (e: Exception) {
                Log.e("SCHEDULER", "Exception while calling scheduling API: ${e.message}", e)
                serverSuccess = false
            }
        } else {
            Log.w("SCHEDULER", "No network/internet. Saving schedules locally as pending.")
        }



        // IF API FAIL → SAVE FULL JSON INTO PENDING TABLE
        // -------------------------------------------------
        if (!serverSuccess) {

            for (i in 0 until actionArray.length()) {
                val o = actionArray.getJSONObject(i)

                val pending = PendingScheduleEntity(
                    school_id = o.getString("school_id"),
                    syear = o.getString("syear"),
                    marking_period_id = o.getString("marking_period_id"),
                    mp = o.getString("mp"),

                    class_id = o.getString("class_id"),
                    class_title = o.getString("class_title"),

                    subjectId = o.getString("subjectId"),
                    headId = o.getString("headId"),

                    course_id = o.getString("course_id"),
                    course_period_id = o.getString("course_period_id"),
                    cp_title = o.getString("cp_title"),

                    teacher_id = o.getString("teacher_id"),
                    teacher_name = o.getString("teacher_name"),

                    student_id = o.getString("student_id"),
                    student_name = o.getString("student_name"),

                    start_date = o.getString("start_date"),
                    created_by = o.getString("created_by"),

                    isCreateScheduling = o.getString("isCreateScheduling"),
                    isUpdateScheduling = o.getString("isUpdateScheduling"),

                    syncStatus = "pending"
                )

                db.pendingScheduleDao().insertSchedule(pending)
            }

            Log.e("SCHEDULER", "API FAILED → Saved ${actionArray.length()} pending schedules locally")
            return@withContext // stop here, no StudentSchedule insertion
        }






        // 4) Insert/update local student_schedule rows based on serverSuccess
        // We'll insert one StudentSchedule per student × cp pair. id format: sch_<ts>_<student>_<cp>
        val nowTs = System.currentTimeMillis()
        val toInsert = mutableListOf<StudentSchedule>()

        tempSelected.forEach { studentId ->
            selectedCourseInfo.forEach { info ->
                if (info.cpId.isNullOrBlank() || info.courseId.isNullOrBlank()) return@forEach

                // check duplicates first (avoid duplicate insertion)
                val exists = db.studentScheduleDao().getAll().any {
                    it.studentId == studentId && it.cpId == info.cpId
                }
                if (exists) {
                    Log.d("SCHEDULER", "Skipping duplicate schedule for student=$studentId cp=${info.cpId}")
                    return@forEach
                }

                val scheduleId = "sch_${nowTs}_${studentId}_${info.cpId}"
                val newSchedule = StudentSchedule(
                    scheduleId = scheduleId,
                    studentId = studentId,
                    cpId = info.cpId!!,
                    courseId = info.courseId!!,
                    scheduleStartDate = nowTs.toString(),
                    scheduleEndDate = null,
                    syncStatus = "complete"
                )
                toInsert.add(newSchedule)

                Log.d(
                    "SCHEDULER",
                    "Will save local schedule: id=$scheduleId student=$studentId cp=${info.cpId} "
                )
            }
        }

        if (toInsert.isNotEmpty()) {
            try {
                db.studentScheduleDao().insertAll(toInsert)

            } catch (e: Exception) {
                Log.e("SCHEDULER", "Error inserting local schedules: ${e.message}", e)
            }
        }
    }




}
