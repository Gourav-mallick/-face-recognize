package com.example.login.repository

import android.content.Context
import android.util.Log
import com.example.login.api.ApiService
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.*
import com.example.login.utility.TripleDESUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DataSyncRepository(private val context: Context) {

    private val TAG = "DataSyncRepository"

    suspend fun fetchAndSaveStudents(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        val rParam = "api/v1/StudentEnrollment/GetStudList"
        val dataParam = "{\"studListParamData\":{\"actionType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"
        val response = apiService.getStudents(rParam, dataParam)

        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            val collection = json.optJSONObject("collection")
            val responseObj = collection?.optJSONObject("response")
            val dataArray = responseObj?.optJSONArray("studentData") ?: JSONArray()

            val studentsList = mutableListOf<Student>()
            val classList = mutableListOf<Class>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val studentId = obj.optString("studentId", "")
                val studentName = obj.optString("studentName", "")
                val classId = obj.optString("classId", "")
                val classShortName = obj.optString("classShortName", "")
                val instId = obj.optString("instId", "")
                studentsList.add(Student(studentId, studentName, classId, instId))
                classList.add(Class(classId, classShortName))
            }

            db.studentsDao().insertAll(studentsList)
            db.classDao().insertAll(classList)
            Log.d(TAG, "Inserted ${studentsList.size} students and ${classList.size} classes.")
            true
        } else {
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
                            obj.optString("instId", "")
                        )
                    )
                }
            }
            db.teachersDao().insertAll(teachersList)
            Log.d(TAG, "Inserted ${teachersList.size} teachers.")
            true
        } else {
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
                Log.w(TAG, "No subject instance data found.")
                return@withContext false
            }

            val coursePeriods = mutableListOf<CoursePeriod>()
            val courses = mutableListOf<Course>()
            val subjects = mutableListOf<Subject>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                subjects.add(Subject(obj.optString("subjectIds"), obj.optString("subjectTitles")))
                courses.add(Course(obj.optString("courseIds"), obj.optString("subjectIds"), obj.optString("courseTitles"), obj.optString("courseTitles")))
                coursePeriods.add(CoursePeriod(obj.optString("cpIds"), obj.optString("courseIds"), obj.optString("classIds"), obj.optString("teacherIds").replace(",", "").trim(), obj.optString("mpId"), obj.optString("mpLongTitle")))
            }

            db.subjectDao().insertAll(subjects)
            db.courseDao().insertAll(courses)
            db.coursePeriodDao().insertAll(coursePeriods)
            Log.d(TAG, "Subjects: ${subjects.size}, Courses: ${courses.size}, CoursePeriods: ${coursePeriods.size}")
            true
        } else {
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
}
