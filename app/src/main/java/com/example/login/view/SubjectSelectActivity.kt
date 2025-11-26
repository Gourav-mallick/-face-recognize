package com.example.login.view


import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
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
import android.widget.Spinner
import com.example.login.db.entity.Course
import com.example.login.db.entity.CoursePeriod
import com.example.login.db.entity.PendingTeacherAllocationEntity


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


        //  Disable back press (both button and gesture)
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

        //  Save app state so that reopening resumes here
        getSharedPreferences("APP_STATE", MODE_PRIVATE)
            .edit()
            .putBoolean("IS_IN_PERIOD_SELECT", true)
            .putString("SESSION_ID", sessionId)
            .apply()

       // setupPeriodDropdown()
        loadCourses()
/*
        binding.btnSubjectAllocation.setOnClickListener {
            openTeacherAllocationPopup()
        }

 */

        binding.btnContinue.setOnClickListener {
            handleContinue()
        }



    }

/*
    //  Load courses from DB
    private fun loadCourses() {
        lifecycleScope.launch {
            val teacherId = db.sessionDao().getSessionById(sessionId)?.teacherId ?: ""

            // 1️⃣ Get course periods for this teacher AND the selected classes
            val assignedCoursePeriods = db.coursePeriodDao().getAllCoursePeriods()
                .filter { cp ->
                    cp.teacherId == teacherId && selectedClasses.contains(cp.classId)
                }

            // 2️⃣ Extract only the courseIds teacher  in selected classes
            val teacherSelectedClassCourseIds = assignedCoursePeriods.map { it.courseId }.toSet()

            // 1️⃣ First try to load teacher's assigned courses
            var courses = db.courseDao().getAllCourses().filter { course ->
                teacherSelectedClassCourseIds.contains(course.courseId)
            }

       // 2️⃣ If teacher has NO assigned courses for this class → load ALL class courses
            // 3️⃣ If no course assigned → Load ALL courses for this class
    /*
            if (courses.isEmpty()) {

                // 1) Get ALL CPs for this class
                val allCpForClass = db.coursePeriodDao().getAllCoursePeriods()
                    .filter { cp -> selectedClasses.contains(cp.classId) }

                // 2) Build popup view
                val inflater = LayoutInflater.from(this@SubjectSelectActivity)
                val view = inflater.inflate(R.layout.dialog_student_not_schedule_checkbox_list, null)
                val container = view.findViewById<LinearLayout>(R.id.containerStudents)

                // 3) Temp selected cpIds
                val selectedCpIds = mutableSetOf<String>()

                allCpForClass.forEach { cp ->
                    selectedCpIds.add(cp.cpId)

                    val course = db.courseDao().getAllCourses().firstOrNull { it.courseId == cp.courseId }
                    val courseName = course?.courseTitle ?: "Course ${cp.courseId}"
                    val cpName = cp.mpLongTitle ?: "Period"

                    val cb = CheckBox(this@SubjectSelectActivity)
                    cb.text = "$courseName\n$cpName (CP: ${cp.cpId})"
                    cb.isChecked = true

                    cb.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedCpIds.add(cp.cpId)
                        else selectedCpIds.remove(cp.cpId)
                    }

                    container.addView(cb)
                }

                // 4) Popup
                AlertDialog.Builder(this@SubjectSelectActivity)
                    .setTitle("Assign Teacher to Courses")
                    .setView(view)
                    .setPositiveButton("OK") { _, _ ->
                        lifecycleScope.launch {
                            if (selectedCpIds.isEmpty()) {
                                showToast("Select at least one course")
                            } else {
                                allocateTeacherToCourse(selectedCpIds)
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

                return@launch
            }



     */

            Log.d("CourseSelectActivity", "Filtered Courses: $courses")

            val adapter = SubjectSelectAdapter(courses) { selectedIds ->
                selectedCourseIds.clear()
                selectedCourseIds.addAll(selectedIds)
            }

            binding.recyclerViewCourses.layoutManager =
                LinearLayoutManager(this@SubjectSelectActivity)

            binding.recyclerViewCourses.adapter = adapter
        }
    }



 */


    // 🔹 Load courses from DB
    private fun loadCourses() {
        lifecycleScope.launch(Dispatchers.IO) {

            val session = db.sessionDao().getSessionById(sessionId)
            val teacherId = session?.teacherId ?: ""

            // 1️⃣ All course periods
            val allCoursePeriods = db.coursePeriodDao().getAllCoursePeriods()

            // 2️⃣ Only CPs for the teacher AND selected classes
            val assignedCoursePeriods = allCoursePeriods.filter { cp ->
                cp.teacherId == teacherId &&          // teacher must match
                        selectedClasses.contains(cp.classId)   // class must match
            }

            // 3️⃣ Load all courses
            val allCourses = db.courseDao().getAllCourses()

            // 4️⃣ Convert CP → Course
            val assignedCourses = assignedCoursePeriods.mapNotNull { cp ->
                allCourses.firstOrNull { it.courseId == cp.courseId }
            }

            // 5️⃣ Update UI
            withContext(Dispatchers.Main) {
                updateCourseUI(assignedCourses)
            }
        }
    }




    private fun updateCourseUI(courses: List<Course>) {

        val adapter = SubjectSelectAdapter(courses) { selectedIds ->
            selectedCourseIds.clear()
            selectedCourseIds.addAll(selectedIds)
        }

        binding.recyclerViewCourses.layoutManager =
            LinearLayoutManager(this@SubjectSelectActivity)

        binding.recyclerViewCourses.adapter = adapter
    }



    //  Handle "Continue" button click
    private fun handleContinue() {

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@SubjectSelectActivity)
            val isMultiClass = selectedClasses.size > 1



            val teacherId = db.sessionDao().getSessionById(sessionId)?.teacherId ?: ""

           // All CPs for this teacher for selected courses
            val validTeacherCps = db.coursePeriodDao().getAllCoursePeriods()
                .filter { cp ->
                    cp.teacherId == teacherId && selectedCourseIds.contains(cp.courseId)
                }
                .map { it.cpId }
                .toSet()

            // 1) Get selected CPIDs from selected courses
            val selectedCourseInfo = db.courseDao().getCourseDetailsForIds(selectedCourseIds)
                .filter { info ->
                    info.cpId != null && validTeacherCps.contains(info.cpId)
                }
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
                    .setTitle("Students Not Schedule : (${notScheduleStudents.size})")
                    .setView(view)
                    .setPositiveButton("OK") { _, _ ->
                        lifecycleScope.launch {

                            var removedCount = 0
                            // REMOVE UNCHECKED STUDENTS FROM ATTENDANCE
                            notScheduleStudents.forEach { s ->
                                if (!tempSelected.contains(s.studentId)) {
                                    removedCount++
                                    Log.d(
                                        "AttendanceLog",
                                        "DELETED → Student ${s.studentId} (${s.studentName}) removed from session $sessionId"
                                    )
                                    db.attendanceDao().deleteAttendanceForStudent(sessionId, s.studentId)
                                }
                            }

                            //  SHOW TOAST IF ANY STUDENTS WERE REMOVED
                            if (removedCount > 0) {
                                showToast("Unchecked $removedCount students, their attendance ignored by the system.")
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
                        showToast("Please select or add a course")

                    }

                    isMultiCourse -> {
                        val courseDetails = db.courseDao().getCourseDetailsForIds(selectedCourseIds)

                        if (courseDetails.isEmpty()) {
                            showToast("No course details found")

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
                            showToast("Course details not found")

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

        // KEEP ONLY CPIDs where:
// 1) teacher matches
// 2) courseId matches selected course
// 3) classId matches selectedClasses
        val validTeacherCpIds = db.coursePeriodDao().getAllCoursePeriods()
            .filter { cp ->
                cp.teacherId == teacherId &&
                        selectedCourseInfo.any { it.courseId == cp.courseId } &&
                        selectedClasses.contains(cp.classId)
            }
            .map { it.cpId }
            .toSet()

        val teacherSelectedInfo = selectedCourseInfo.filter { info ->
            info.cpId != null && validTeacherCpIds.contains(info.cpId)
        }

        tempSelected.forEach { studentId ->
            teacherSelectedInfo.forEach { info ->
                // skip invalid cp/course rows
                if (info.cpId.isNullOrBlank() || info.courseId.isNullOrBlank()) {
                    Log.d("SCHEDULER", "Skipping invalid info for student=$studentId cpId=${info.cpId} courseId=${info.courseId}")
                    return@forEach
                }

                val student =db.studentsDao().getStudentById(studentId)
                val student_classId=student?.classId
                val student_inst=student?.instId

                val syear= student_inst?.let {db.instituteDao().getInstituteYearById(it)}


                var Student_className = ""
                if (!student_classId.isNullOrBlank()) {
                    val classTable = db.classDao().getClassById(student_classId)
                    Student_className = classTable?.classShortName ?: ""
                }

                val obj = JSONObject()
                // Keep these fields similar to sample payload you provided.
                obj.put("school_id", student_inst)
                obj.put("syear", syear?:"")
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


                        val msgArr = respJson
                            .optJSONObject("collection")
                            ?.optJSONObject("response")
                            ?.optJSONArray("msgArr")

                        val successMsg = if (msgArr != null && msgArr.length() > 0) {
                            msgArr.getString(0)   // <-- your required message
                        } else {
                            "Students scheduled successfully"
                        }
                        showToast(successMsg)

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

            showToast("Students scheduled successfully")


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


    private fun showToast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }




    private suspend fun allocateTeacherToCourse(selectedCpIds: Set<String>) = withContext(Dispatchers.IO) {

        Log.e("ALLOC_TEACHER", "START allocateTeacherToCourse")
        Log.e("ALLOC_TEACHER", "Selected CP IDs = $selectedCpIds")

        val session = db.sessionDao().getSessionById(sessionId)
        val teacherId = session?.teacherId ?: ""
        Log.e("ALLOC_TEACHER", "Session teacherId = $teacherId")

        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "")!!
        val hash = prefs.getString("hash", "")!!

        Log.e("ALLOC_TEACHER", "BaseURL = $baseUrl   Hash = $hash")

        // Build JSON (same as old code)
        val req = JSONObject().apply {
            put("actionType", "addTeacher")
            put("actionData", JSONArray(selectedCpIds.toList()))
            put("otherDetails", JSONObject().apply {
                put("comment", "add another teacher to this course")
                put("utilityName", "Manage Teacher Allocation")
                put("user_id", "199")
                put("teacherToAdd", teacherId)
            })
        }

        val jsonString = req.toString()
        Log.e("ALLOC_TEACHER", "Request JSON = $jsonString")
        // Check network
        val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(this@SubjectSelectActivity)
        val hasInternet = if (hasNetwork) CheckNetworkAndInternetUtils.hasInternetAccess() else false
        Log.e("ALLOC_TEACHER", "NetworkAvailable=$hasNetwork  Internet=$hasInternet")

        var serverSuccess = false

        if (hasNetwork && hasInternet) {
            try {
                Log.e("ALLOC_TEACHER", "Trying API call now…")
                val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)
                val body = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json"), jsonString
                )
                val response = api.postTeacherAllocation(body)

                Log.e("ALLOC_TEACHER", "API Response code = ${response.code()}")

                if (response.isSuccessful && response.body() != null) {

                    val respStr = response.body()!!.string()
                    Log.e("ALLOC_TEACHER", "API Response body = $respStr")
                    val respJson = JSONObject(respStr)

                    val status = respJson
                        .optJSONObject("collection")
                        ?.optJSONObject("response")
                        ?.optJSONObject("updationStatus")
                        ?.optString("status")

                    Log.e("ALLOC_TEACHER", "Server status = $status")

                    if (status == "SUCCESS") {
                        serverSuccess = true
                        Log.e("ALLOC_TEACHER", "Teacher allocation SUCCESS from server")
                        showToast("Teacher allocated successfully")
                    }else {
                        Log.e("ALLOC_TEACHER", "Server returned FAILURE / null")
                    }
                }else {
                    Log.e("ALLOC_TEACHER", "API not successful. Code = ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("ALLOC_TEACHER", "Exception during API call = ${e.message}")
                serverSuccess = false
            }
        }else {
            Log.e("ALLOC_TEACHER", "Skipping API call → No network OR no internet")
        }


        // If FAIL → SAVE LOCALLY
        if (!serverSuccess) {
            Log.e("ALLOC_TEACHER", "Saving teacher allocation OFFLINE")
            val pending = PendingTeacherAllocationEntity(
                teacherId = teacherId,
                cpIds = selectedCpIds.joinToString(","),
                jsonPayload = jsonString,
                syncStatus = "pending"
            )

            db.pendingTeacherAllocationDao().insert(pending)

            Log.e("ALLOC_TEACHER", "Inserted into pending_teacher_allocation table")

            // STILL update local CPs for immediate UI
            selectedCpIds.forEach { cpId ->
                Log.e("ALLOC_TEACHER", "Updating local CP teacherId for cpId = $cpId")
                val cp = db.coursePeriodDao().getCoursePeriodByCpId(cpId)
                if (cp != null) {
                    val updated = cp.copy(teacherId = teacherId)
                    db.coursePeriodDao().insertAll(listOf(updated))
                    Log.e("ALLOC_TEACHER", "Local CP updated")
                }else {
                    Log.e("ALLOC_TEACHER", "cpId $cpId not found in DB")
                }
            }

            showToast("Saved offline. Will sync when internet is available.")
        }

        withContext(Dispatchers.Main) { loadCourses() }
    }



    private fun openTeacherAllocationPopup() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_teacher_allocation, null)

        val spinnerMp = view.findViewById<Spinner>(R.id.spinnerMp)
        val container = view.findViewById<LinearLayout>(R.id.containerCourses)

        lifecycleScope.launch {

            // Load MP list from CoursePeriods
            val allCps = db.coursePeriodDao().getAllCoursePeriods()
            // Build a list of pairs (mpId, mpLongTitle)
            val mpItems = allCps
                .filter { it.mpId != null && it.mpLongTitle != null }
                .groupBy { it.mpId }
                .map { entry ->
                    Pair(entry.key!!, entry.value.first().mpLongTitle!!) // mpId -> mpLongTitle
                }

            spinnerMp.adapter = ArrayAdapter(
                this@SubjectSelectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                mpItems.map { it.second }  // show mpLongTitle
            )


            // Handle MP selection change
            spinnerMp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>, view: View?, position: Int, id: Long
                ) {
                    val selectedMpId = mpItems[position].first  // the actual MP ID
                    lifecycleScope.launch {
                        loadCpCheckboxes(selectedMpId, container)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }



            AlertDialog.Builder(this@SubjectSelectActivity)
                .setTitle("Teacher Allocation")
                .setView(view)
                .setPositiveButton("OK") { _, _ ->
                    saveTeacherAllocation(container)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private suspend fun loadCpCheckboxes(mpId: String, container: LinearLayout) {
        withContext(Dispatchers.Main) { container.removeAllViews() }

        val cps = db.coursePeriodDao().getAllCoursePeriods()
            .filter { it.mpId == mpId }

        cps.forEach { cp ->
            val course = db.courseDao().getAllCourses()
                .firstOrNull { it.courseId == cp.courseId }

            val cb = CheckBox(this)
            cb.text = course?.courseShortName ?: "Course"
            cb.tag = cp.cpId

            withContext(Dispatchers.Main) {
                container.addView(cb)
            }
        }
    }


    private fun saveTeacherAllocation(container: LinearLayout) {
        val selectedCpIds = mutableSetOf<String>()

        for (i in 0 until container.childCount) {
            val cb = container.getChildAt(i) as CheckBox
            if (cb.isChecked) selectedCpIds.add(cb.tag.toString())
        }

        if (selectedCpIds.isEmpty()) {
            showToast("Select at least one course period")
            return
        }

        lifecycleScope.launch {
            allocateTeacherToCourse(selectedCpIds)
        }
    }



}
