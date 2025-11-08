package com.example.login.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Student
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.example.login.db.entity.Course
import com.example.login.db.entity.CoursePeriod
import com.example.login.db.entity.Subject
import com.example.login.db.entity.Teacher
import com.example.login.db.entity.Class
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.TelephonyManager
import java.util.*
import android.os.Build
import android.provider.Settings
import android.view.View
import com.example.login.R
import com.example.login.repository.DataSyncRepository
import com.example.login.utility.CheckNetworkAndInternetUtils
import com.example.login.utility.TripleDESUtility
import kotlinx.coroutines.delay


class SelectInstituteActivity : AppCompatActivity() {

    private lateinit var instituteSelectionLayout: LinearLayout
 //   private lateinit var edtUsername: EditText
  //  private lateinit var edtPassword: EditText
    private lateinit var btnSync: Button
    private lateinit var progressBar: ProgressBar

    private val selectedInstitutes = mutableSetOf<String>()
    private val TAG = "SELECT_INSTITUTE"
  //  private val HASH = "trr36pdthb9xbhcppyqkgbpkq"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_institute)

        instituteSelectionLayout = findViewById(R.id.instituteSelectionLayout)
  //      edtUsername = findViewById(R.id.usernametap)
   //     edtPassword = findViewById(R.id.passwordtap)
        btnSync = findViewById(R.id.btnLogin)
        progressBar = findViewById(R.id.progressBar)
        // SearchView
        val searchInstitute = findViewById<SearchView>(R.id.searchInstitute)

        // 🔹 Get shared preferences
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val savedUsername = prefs.getString("username", "")
        val savedPassword = prefs.getString("password", "")
        val baseUrl = prefs.getString("baseUrl", "") ?: ""
        val HASH=prefs.getString("hash", "")


        // 🔹 Autofill saved credentials
       // edtUsername.setText(savedUsername)
      //  edtPassword.setText(savedPassword)

        // 🔹 Get data from intent
        val schoolIds = intent.getStringArrayListExtra("schoolIds") ?: arrayListOf()
        val schoolShortNames = intent.getStringArrayListExtra("schoolShortNames") ?: arrayListOf()

        // store all views for filtering
        val allInstituteViews = mutableListOf<View>()

        // 🔹 Dynamically create checkbox list
        for (i in schoolIds.indices) {
            val view = layoutInflater.inflate(R.layout.item_institute, instituteSelectionLayout, false)
            val chkSelect = view.findViewById<CheckBox>(R.id.cbSelectInstitute)
            val tvSchoolShortName = view.findViewById<TextView>(R.id.tvSchoolShortName)
            val tvSchoolId = view.findViewById<TextView>(R.id.tvSchoolId)

            tvSchoolShortName.text = schoolShortNames[i]
            tvSchoolId.text = "Institute ID: ${schoolIds[i]}"

            chkSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedInstitutes.add(schoolIds[i])
                else selectedInstitutes.remove(schoolIds[i])
            }

            instituteSelectionLayout.addView(view)
            allInstituteViews.add(view)
        }

        //  Search filter logic
        searchInstitute.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText?.trim()?.lowercase(Locale.getDefault()) ?: ""
                for (view in allInstituteViews) {
                    val name = view.findViewById<TextView>(R.id.tvSchoolShortName).text.toString().lowercase(Locale.getDefault())
                    val idText = view.findViewById<TextView>(R.id.tvSchoolId).text.toString().lowercase(Locale.getDefault())

                    // Match if query is part of name or ID
                    view.visibility = if (name.contains(query) || idText.contains(query)) View.VISIBLE else View.GONE
                }
                return true
            }
        })

        // 🔹 Sync button click
        btnSync.setOnClickListener {
        //    val enteredUser = edtUsername.text.toString().trim()
        //    val enteredPass = edtPassword.text.toString().trim()

            // Show progress and disable button
            progressBar.visibility = ProgressBar.VISIBLE
            btnSync.isEnabled = false

            // ✅ NEW: Check network connectivity first
            if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this)) {
                showToast("No network connection. Please check your network.")
                progressBar.visibility = ProgressBar.GONE
                btnSync.isEnabled = true
                return@setOnClickListener
            }
/*
            // 🔸 Validate fields
            if (enteredUser.isEmpty() || enteredPass.isEmpty()) {
                showToast("Please enter username and password")
                return@setOnClickListener
            }

 */
            if (selectedInstitutes.isEmpty()) {
                showToast("Please select at least one institute")
                progressBar.visibility = ProgressBar.GONE
                btnSync.isEnabled = true
                return@setOnClickListener
            }
/*
            // 🔸 Validate with SharedPreferences
            if (enteredUser != savedUsername || enteredPass != savedPassword) {
                showToast("Invalid username or password")
                return@setOnClickListener
            }

 */
            //  Save selected institute IDs to SharedPreferences
            val instIds = selectedInstitutes.joinToString(",")


            // Normalize baseUrl with triple slashes
            val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
                baseUrl.removeSuffix("/") + "///"
            } else {
                "$baseUrl///"
            }

            // 🔹 Build query data
            val rParam = "api/v1/StudentEnrollment/GetStudList"
            val dataParam = "{\"studListParamData\":{\"actionType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"


            val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$dataParam"
            Log.d(TAG, "REQUEST_URL: $fullUrl")


            Log.d(TAG, "SYNC_CALL: instId=$instIds")



// Inside your btnSync.setOnClickListener -> lifecycleScope.launch(Dispatchers.IO)
            lifecycleScope.launch(Dispatchers.IO) {
                try {

                    val hasInternet = CheckNetworkAndInternetUtils.hasInternetAccess()
                    if (!hasInternet) {
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = ProgressBar.GONE
                            btnSync.isEnabled = true
                            showToast("No internet access. Please check your connection.")
                        }
                        return@launch
                    }
                    val retrofit = ApiClient.getClient(normalizedBaseUrl, HASH)
                    val apiService = retrofit.create(ApiService::class.java)
                    val db = AppDatabase.getDatabase(this@SelectInstituteActivity)

                    // Call multiple APIs sequentially
                    val repository = DataSyncRepository(this@SelectInstituteActivity)

                    val studentsDataFatchOk = repository.fetchAndSaveStudents(apiService, db, instIds)
                    val teachersDataFatchOk = repository.fetchAndSaveTeachers(apiService, db, instIds)
                    val subjectsDataFatchOk = repository.syncSubjectInstances(apiService, db)
                    val deviceDataFatchOk = fetchDeviceDataToServer(apiService, db, normalizedBaseUrl, instIds)
                    Log.d(TAG, "All data synced and device config stored locally.")

                    val allApiCallOk = studentsDataFatchOk && teachersDataFatchOk && subjectsDataFatchOk && deviceDataFatchOk
                    delay(2000)
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = ProgressBar.GONE
                        btnSync.isEnabled = true


                        if(allApiCallOk){

                            prefs.edit()
                                .putString("selectedInstituteIds", instIds)
                                .putString("selectedInstituteNames", schoolShortNames.joinToString(","))
                                .apply()
                            Log.d(TAG, "Saved selected institutes: $instIds")

                            showToast("Sync completed successfully!")

                            // Navigate to AttendanceActivity
                            val intent= Intent(this@SelectInstituteActivity, AttendanceActivity::class.java)
                            startActivity(intent)
                            finish()
                        }else{
                            showToast("Partial sync detected. Please try again.")
                        }
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = ProgressBar.GONE
                        btnSync.isEnabled = true
                        showToast("Error: ${e.message}")
                    }
                    Log.e(TAG, "SYNC_EXCEPTION: ${e.message}", e)
                }
            }
        }
    }

    // ✅  Safe toast helper that works from any thread
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this@SelectInstituteActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    // get students and save to db
    private suspend fun fetchAndSaveStudents(
        apiService: ApiService,
        db: AppDatabase,
        baseUrl: String,
        instIds: String
    ) :Boolean  {
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
            Log.d(TAG, "Inserted ${studentsList} students.")
            Log.d(TAG, "Inserted ${classList} classes.")
            return true
        } else {
            Log.e(TAG, "STUDENT_API_FAILED: ${response.errorBody()?.string()}")
            return false
        }
    }




//Get teachers and save to db
    private suspend fun fetchAndSaveTeachers(
        apiService: ApiService,
        db: AppDatabase,
        normalizedBaseUrl: String,
        instIds: String
    ) :Boolean  {
        val rParam = "api/v1/User/GetUserRegisteredDetails"
        val dataParam = "{\"userRegParamData\":{\"userType\":\"staff\",\"registrationType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"

        val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$dataParam"
        Log.d(TAG, "REQUEST_TEACHER_URL: $fullUrl")

        val response = apiService.getTeachers(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            val collection = json.optJSONObject("collection")
            val responseObj = collection?.optJSONObject("response")
            val dataArray = responseObj?.optJSONArray("userRegisteredData") ?: JSONArray()

            val teachersList = mutableListOf<Teacher>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)

                val staffProfile= obj.optString("staffProfile", "")
                if (staffProfile.equals("teacher", ignoreCase = true)) {
                    // Only process if userProfile is "teacher"
                    val staffId = obj.optString("staffId", "")
                    val staffName = obj.optString("staffName", "")
                    val instId = obj.optString("instId", "")
                    teachersList.add(Teacher(staffId, staffName, instId))
                }

            }
            db.teachersDao().insertAll(teachersList)
            Log.d(TAG, "Inserted teachers ${teachersList} .")
            return true
        } else {
            Log.e(TAG, "TEACHER_API_FAILED: ${response.errorBody()?.string()}")
            return false
        }
    }



    //get subject instances data and normalize into different table like coursePero
    private suspend fun syncSubjectInstances(
        apiService: ApiService,
         db: AppDatabase,
        normalizedBaseUrl: String,
        instIds: String
    ) :Boolean  {
        val rParam = "api/v1/CoursePeriod/SubjectInstances"
        val dataParam = "{\"cpParamData\":{\"actionType\":\"markCpAttendance2\"}}"

        val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$dataParam"
        Log.d(TAG, "REQUEST_subjectInstance_URL: $fullUrl")



        val response = apiService.getSubjectInstances(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
                    val jsonString = response.body()!!.string()
                    Log.d(TAG, "SUBJECT_INSTANCE_RESPONSE: $jsonString")

                    val json = JSONObject(jsonString)
                    val collection = json.optJSONObject("collection")
                    val responseObj = collection?.optJSONObject("response")
                    val dataArray = responseObj?.optJSONArray("subjectInstancesData")

                    if (dataArray == null || dataArray.length() == 0) {
                        Log.w(TAG, "No subject instance data found.")
                        Toast.makeText(this@SelectInstituteActivity, "No subject instance data found.", Toast.LENGTH_LONG).show()
                        return false
                    }


                    val coursePeriodList = mutableListOf<CoursePeriod>()
                    val courseList = mutableListOf<Course>()
                    val subjectList = mutableListOf<Subject>()
                   // val classList = mutableListOf<Class>()

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

                        // Normalize data
                        subjectList.add(Subject(subjectId, subjectTitle))
                        courseList.add(Course(courseId, subjectId, courseTitle, courseTitle))
                       // classList.add(Class(classId, classShortName))
                        coursePeriodList.add(CoursePeriod(cpId, courseId, classId, teacherId, mpId,mpLongTitle))
                    }

                    // Save all in DB
                    db.subjectDao().insertAll(subjectList)
                    db.courseDao().insertAll(courseList)
                   // db.classDao().insertAll(classList)
                    db.coursePeriodDao().insertAll(coursePeriodList)


                    Log.d(TAG, "DB_INSERT_SUCCESS: ${coursePeriodList} records saved")
                    Log.d(TAG, "DB_INSERT_Subjects: ${subjectList} records saved")
                    Log.d(TAG, "DB_INSERT_course: ${courseList} records saved")
                  //  Log.d(TAG, "DB_INSERT_class: ${classList} records saved")
                    return true
        } else {
                    Log.e(TAG, "API Error: ${response.errorBody()?.string()}")
                    return false
        }


    }



   private suspend fun fetchDeviceDataToServer(
        apiService: ApiService,
        db: AppDatabase,
        normalizedBaseUrl: String,
        instIds: String
    ):Boolean {
        val rParam = "api/v1/Hardware/DeviceUtilityMgmt"
        val dataParam = getDeviceUtilityQueryParams(this)

       val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$dataParam"
       Log.d(TAG, "HARDWARE_REQUEST_URL: $fullUrl")

        val response = apiService.getDeveiceDataToserver(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()

            val json = JSONObject(jsonString)
            Log.d(TAG, "HARDWARE_RESPONSE: $jsonString")
            val collection = json.optJSONObject("collection")
            Log.d(TAG, "HARDWARE_COLLECTION_RESPONSE: $collection")
            val responseObj = collection?.optJSONObject("response")
            Log.d(TAG, "RESPONSE: $responseObj")
            val hwMgmtData = responseObj?.optJSONObject("hwMgmtData")
            Log.d(TAG, "HW_MGMT_DATA: $hwMgmtData")


            if (hwMgmtData != null) {
                val status = hwMgmtData.optString("status")
                val cfg = hwMgmtData.optJSONObject("cfg")
                val deviceDetails = hwMgmtData.optJSONObject("deviceDetails")

                val passcode=cfg?.optString("passCode")
                val faciCode=cfg?.optString("faciCode")
                val instType=cfg?.optString("instType")
                val deconfigstr = cfg?.optString("deconfigstr")

                if (!deconfigstr.isNullOrEmpty()) {
                    val decryptedStr = TripleDESUtility().getDecryptedStr(deconfigstr)
                    Log.d(TAG, "Decrypted Config: $decryptedStr")

                    // Example: "CODE,ABCD12,0345"
                    val elements = decryptedStr.split(",")
                    if (elements.size >= 2) {
                        val passCode = elements[1].trim()
                        val faciCode = elements.getOrNull(2)?.trim() ?: ""
                        val hexPassCode = convertAsciiToHex(passCode)

                        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                        prefs.edit()
                            .putString("cpass", hexPassCode)
                            .putString("passCode", passCode)
                            .putString("faciCode", faciCode)
                            .apply()

                        Log.d(TAG, "Saved passCode: $passCode → HEX: $hexPassCode")

                        val verify = prefs.getString("cpass", null)
                        Log.d(TAG, "VERIFY_PREF_AFTER_SAVE: $verify")

                    } else {
                        Log.e(TAG, "Invalid decrypted config format: $decryptedStr")
                    }
                }
            } else {
                Log.e(TAG, "No hwMgmtData found in response!")
            }
            return true
        }
            else {
            Log.e(TAG, "STUDENT_API_FAILED: ${response.errorBody()?.string()}")
             return false
            }
    }



    fun convertAsciiToHex(input: String): String {
        return input.map { it.code.toString(16).uppercase() }.joinToString("")
    }



    @SuppressLint("HardwareIds", "MissingPermission")
    fun getDeviceUtilityQueryParams(context: Context): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentDate = sdf.format(Date())

        val telephonyManager = context.getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val imei = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) telephonyManager.imei ?: "N/A"
            else telephonyManager.deviceId ?: "N/A"
        } catch (e: Exception) { "N/A" }

        val serialNo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial()
            else Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }

        val bm = context.getSystemService(BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && bm != null)
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) else -1

        val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val connectivity = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                when {
                    capabilities == null -> "NO_CONNECTION"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    else -> "UNKNOWN"
                }
            } else {
                val info = connectivityManager.activeNetworkInfo
                when {
                    info == null || !info.isConnected -> "NO_CONNECTION"
                    info.type == ConnectivityManager.TYPE_WIFI -> "WIFI"
                    info.type == ConnectivityManager.TYPE_MOBILE -> "MOBILE"
                    else -> "UNKNOWN"
                }
            }
        } catch (e: Exception) { "UNKNOWN" }

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "1.0" }

        // ✅ Return proper JSON string just like server expects
        return """
        {
          "deviceUtilityParamData": {
            "device_srno": "$serialNo",
            "imei_no": "$imei",
            "app_id": "${context.packageName}",
            "app_name": "periodSync",
            "app_version": "$appVersion",
            "battery_level": "$batteryLevel",
            "last_recharge_date": "$currentDate",
            "validity_date": "$currentDate",
            "connectivity": "$connectivity",
            "last_sync": "${dateFormat.format(Date())}",
            "gps_corordinates": "",
            "device_place": "TestLab",
            "status_info": "rec_to_sync:0,student_reg_cnt:0,staff_reg_cnt:0,sub_instance_cnt:0,enrolled_student_cnt:0,schedule_student_cnt:0,institute_code:0",
            "req_type": "CFG"
          }
        }
    """.trimIndent()
    }

}
