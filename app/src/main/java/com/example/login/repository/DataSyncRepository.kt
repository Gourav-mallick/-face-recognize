package com.example.login.repository

import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.*
import com.example.login.utility.TripleDESUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat

class DataSyncRepository(private val context: Context) {

    private val TAG = "DataSyncRepository"

    suspend fun fetchAndSaveStudents(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        val rParam = "api/v1/User/GetUserRegisteredDetails"
        val dataParam = "{\"userRegParamData\":{\"userType\":\"student\",\"registrationType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"
        val response = apiService.getStudents(rParam, dataParam)

        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            val collection = json.optJSONObject("collection")
            val responseObj = collection?.optJSONObject("response")
            val dataArray = responseObj?.optJSONArray("userRegisteredData") ?: JSONArray()

            val studentsList = mutableListOf<Student>()
            val classList = mutableListOf<Class>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val studentId = obj.optString("studentId", "")
                val studentName = obj.optString("studentName", "")
                val classId = obj.optString("classId", "")
                val classShortName = obj.optString("userClassShortName", "")
                val fingerType= obj.optString("fingerType", "")
                val fingerData= obj.optString("fingerData", "")
                val instId = instIds
                studentsList.add(Student(studentId, studentName, classId, instId,fingerType,fingerData))
                classList.add(Class(classId, classShortName))
            }

            db.studentsDao().insertAll(studentsList)
            db.classDao().insertAll(classList)
            Log.d(TAG, "Inserted ${studentsList.size} students and ${classList.size} classes.")
            Log.d(TAG, "Inserted ${studentsList} students.")
            true
        } else {
            showToast("Unable to fetch student data. Please try again.")
            Log.e(TAG, "STUDENT_API_FAILED: ${response.errorBody()?.string()}")
            false
        }
    }

    suspend fun fetchAndSaveTeachers(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        val rParam = "api/v1/User/GetUserRegisteredDetails"
        val dataParam = "{\"userRegParamData\":{\"userType\":\"staff\",\"registrationType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"
        val response = apiService.getTeachers(rParam, dataParam)

        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            val dataArray = json.optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONArray("userRegisteredData") ?: JSONArray()

            val teachersList = mutableListOf<Teacher>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                if (obj.optString("staffProfile", "").equals("teacher", ignoreCase = true)) {
                    teachersList.add(
                        Teacher(
                            obj.optString("staffId", ""),
                            obj.optString("staffName", ""),
                            instIds,
                            obj.optString("fingerType", ""),
                            obj.optString("fingerData", "")
                        )
                    )
                }
            }
            db.teachersDao().insertAll(teachersList)
            Log.d(TAG, "Inserted ${teachersList.size} teachers.")
            Log.d(TAG, "Inserted ${teachersList} teachers.")
            true
        } else {
            showToast("Unable to fetch teacher data. Please try again.")
            Log.e(TAG, "TEACHER_API_FAILED: ${response.errorBody()?.string()}")
            false
        }
    }

    suspend fun syncSubjectInstances(
        apiService: ApiService,
        db: AppDatabase
    ): Boolean = withContext(Dispatchers.IO) {
        val rParam = "api/v1/CoursePeriod/SubjectInstances"
        val dataParam = "{\"cpParamData\":{\"actionType\":\"markCpAttendance2\"}}"
        val response = apiService.getSubjectInstances(rParam, dataParam)

        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            val dataArray = json.optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONArray("subjectInstancesData") ?: JSONArray()

            if (dataArray.length() == 0) {
                showToast("No subject instance data found.")
                Log.w(TAG, "No subject instance data found.")
                return@withContext false
            }

            val coursePeriodList = mutableListOf<CoursePeriod>()
            val courseList = mutableListOf<Course>()
            val subjectList = mutableListOf<Subject>()

            val teacherClassMapList = mutableListOf<TeacherClassMap>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)

                val cpId = obj.optString("cpIds")
                val courseId = obj.optString("courseIds")
                val subjectId = obj.optString("subjectIds")
                val subjectTitle = obj.optString("subjectTitles")
                val courseTitle = obj.optString("courseTitles")
                val classId = obj.optString("classIds")
                val classShortName = obj.optString("classShortNames")
                val mpId = obj.optString("mpId")
                val mpLongTitle = obj.optString("mpLongTitle")
                val teacherId = obj.optString("teacherIds").replace(",", "").trim()

                subjectList.add(Subject(subjectId, subjectTitle))
                courseList.add(Course(courseId, subjectId, courseTitle, courseTitle))
                coursePeriodList.add(CoursePeriod(cpId, courseId, classId, teacherId, mpId, mpLongTitle))

                // 👇 NEW MAPPING
                if (teacherId.isNotEmpty()) {
                    teacherClassMapList.add(
                        TeacherClassMap(
                            teacherId = teacherId,
                            classId = classId
                        )
                    )
                }
            }


            db.subjectDao().insertAll(subjectList)
            db.courseDao().insertAll(courseList)
            db.coursePeriodDao().insertAll(coursePeriodList)

            db.teacherClassMapDao().clear()
            db.teacherClassMapDao().insertAll(teacherClassMapList)

          //  val teacherClassMap = db.teacherClassMapDao().getAllTeacherClassMaps()
          //  Log.d(TAG, "Teacher-Class mapping data: ${teacherClassMap}")
            Log.d(TAG, "Subjects: ${subjectList.size}, Courses: ${courseList.size}, CoursePeriods: ${coursePeriodList.size}")
            Log.d(TAG, "Teacher-Class mapping saved: ${teacherClassMapList.size}")

            true
        } else {
            showToast("Unable to fetch class schedule. Please try again.")
            Log.e(TAG, "SUBJECT_INSTANCE_API_FAILED: ${response.errorBody()?.string()}")
            false
        }
    }

    suspend fun fetchDeviceDataToServer(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        val rParam = "api/v1/Hardware/DeviceUtilityMgmt"
        val dataParam = (context as? com.example.login.view.SelectInstituteActivity)?.getDeviceUtilityQueryParams(context)
            ?: return@withContext false

        val response = apiService.getDeveiceDataToserver(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            Log.d(TAG, "HARDWARE_RESPONSE: $jsonString")

            val hwMgmtData = json.optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONObject("hwMgmtData")

            if (hwMgmtData != null) {
                val cfg = hwMgmtData.optJSONObject("cfg")
                val deconfigstr = cfg?.optString("deconfigstr")
                if (!deconfigstr.isNullOrEmpty()) {
                    val decryptedStr = TripleDESUtility().getDecryptedStr(deconfigstr)
                    Log.d(TAG, "Decrypted Config: $decryptedStr")
                }
            }
            true
        } else {
            Log.e(TAG, "DEVICE_API_FAILED: ${response.errorBody()?.string()}")
            false
        }
    }


    suspend fun fetchAndSaveStudentSchedulingData(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rParam = "api/v1/Schedule/GetStudList"
            val dataParam =
                "{\"schedulingParamData\":{\"actionType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"

            val response = apiService.getStudentScheduleList(rParam, dataParam)

            if (!response.isSuccessful || response.body() == null) {
                Log.e(TAG, "SCHEDULE_API_FAILED: ${response.errorBody()?.string()}")
                return@withContext false
            }

            val jsonString = response.body()!!.string()
            Log.d(TAG, "RAW_SCHEDULE_JSON: $jsonString")

            val json = JSONObject(jsonString)
            val dataArray = json
                .optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONArray("studentSchedulingData") ?: JSONArray()

            val scheduleList = mutableListOf<StudentSchedule>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                scheduleList.add(
                    StudentSchedule(
                        scheduleId = obj.optString("scheduleId", ""),
                        studentId = obj.optString("studentId", ""),
                        cpId = obj.optString("cpId", ""),
                        courseId = obj.optString("courseId", ""),
                        scheduleStartDate = obj.optString("scheduleStartDate", ""),
                        scheduleEndDate = obj.optString("scheduleEndDate", ""),
                        syncStatus = "complete"
                    )
                )
            }

            db.studentScheduleDao().insertAll(scheduleList)
            Log.d(TAG, "Saved ${scheduleList.size} student schedule rows")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "SCHEDULE_EXCEPTION: ${e.message}")
            return@withContext false
        }
    }



    private fun showToast(message: String) {
        Handler(context.mainLooper).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }


    suspend fun syncPendingStudentSchedules(context: Context) = withContext(Dispatchers.IO) {


        Log.e("AUTO_SYNC", "syncPendingStudentSchedules() CALLED")

        val db = AppDatabase.getDatabase(context)
        Log.e("AUTO_SYNC", "Fetching pending schedules from Room")

        // fetch pending schedules from new table
        val pendingList = db.pendingScheduleDao().getPendingSchedules()

        Log.e("AUTO_SYNC", "PendingScheduleEntity count = ${pendingList.size}")
        if (pendingList.isEmpty()) {
            Log.i("SCHEDULER_SYNC", "No pending schedules to sync (PendingScheduleEntity empty)")
            return@withContext
        }

        val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "")!!
        val hash = prefs.getString("hash", "")!!

        val apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

        // Directly build payload from PendingScheduleEntity (NO EXTRA MAPPING)
        val actionArray = JSONArray()

        pendingList.forEach { p ->

            val obj = JSONObject()

            // copy all fields exactly as stored
            obj.put("school_id",        p.school_id)
            obj.put("syear",            p.syear)
            obj.put("marking_period_id",p.marking_period_id)
            obj.put("mp",               p.mp)

            obj.put("class_id",         p.class_id)
            obj.put("class_title",      p.class_title)

            obj.put("subjectId",        p.subjectId)
            obj.put("headId",           p.headId)

            obj.put("course_id",        p.course_id)
            obj.put("course_period_id", p.course_period_id)
            obj.put("cp_title",         p.cp_title)

            obj.put("teacher_id",       p.teacher_id)
            obj.put("teacher_name",     p.teacher_name)

            obj.put("student_id",       p.student_id)
            obj.put("student_name",     p.student_name)

            obj.put("start_date",       p.start_date)
            obj.put("created_by",       p.created_by)

            obj.put("isCreateScheduling", p.isCreateScheduling)
            obj.put("isUpdateScheduling", p.isUpdateScheduling)

            actionArray.put(obj)
        }

        val bodyJson = JSONObject().apply {
            put("smParamDataObj", JSONObject().apply {
                put("actionType", "addUpdateStudentSubjectSchedulingTblDetails")
                put("actionData", actionArray)
            })
        }

        val requestBody = okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json"),
            bodyJson.toString()
        )

        Log.e("SCHEDULER_SYNC", "Sending ${pendingList.size} pending schedules...\n$bodyJson")

        try {
            val response = apiService.postStudentSubjectSchedule(body = requestBody)

            if (response.isSuccessful && response.body() != null) {

                val respStr = response.body()!!.string()
                Log.e("SCHEDULER_SYNC", "Response: $respStr")

                val respJson = JSONObject(respStr)
                val status = respJson.optJSONObject("collection")
                    ?.optJSONObject("response")
                    ?.optString("status")
                    ?: respJson.optJSONObject("collection")
                        ?.optJSONObject("response")
                        ?.optString("statusMsg")

                if (status.equals("SUCCESS", true)) {

                    pendingList.forEach {
                        db.pendingScheduleDao().updateSyncStatus(it.id, "complete")

                        Log.e("AUTO_SYNC", "✔ Server SUCCESS — Updating pending rows to complete")

                    }

                    Log.e("SCHEDULER_SYNC", "✔ Pending schedules synced successfully")
                } else {
                    Log.e("SCHEDULER_SYNC", "❌ Server returned FAILURE - will retry")
                }
            }
        } catch (e: Exception) {
            Log.e("SCHEDULER_SYNC", "Exception: ${e.message}")
        }
    }



    suspend fun syncPendingTeacherAllocation(context: Context) = withContext(Dispatchers.IO) {

        val db = AppDatabase.getDatabase(context)
        val pending = db.pendingTeacherAllocationDao().getPending()

        if (pending.isEmpty()) return@withContext

        val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "")!!
        val hash = prefs.getString("hash", "")!!

        val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

        pending.forEach { p ->
            try {
                val body = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json"),
                    p.jsonPayload
                )

                val response = api.postTeacherAllocation(body)

                if (response.isSuccessful && response.body() != null) {
                    val respStr = response.body()!!.string()
                    val respJson = JSONObject(respStr)
                    val status = respJson
                        .optJSONObject("collection")
                        ?.optJSONObject("response")
                        ?.optJSONObject("updationStatus")
                        ?.optString("status")

                    if (status == "SUCCESS") {
                        db.pendingTeacherAllocationDao().updateStatus(p.id, "complete")
                    }
                }

            } catch (e: Exception) {
                // keep pending
            }
        }
    }



}
