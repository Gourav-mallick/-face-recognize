package com.example.login.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.login.api.ApiClient
import com.example.login.api.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import com.example.login.R
import com.example.login.utility.CheckNetworkAndInternetUtils

class LoginActivity : AppCompatActivity() {

    private val HASH = "trr36pdthb9xbhcppyqkgbpkq" // Sent in header
    private val TAG = "LOGIN_APP"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val edtUrl = findViewById<EditText>(R.id.edtUrl)
        val edtUser = findViewById<EditText>(R.id.edtUsername)
        val edtPass = findViewById<EditText>(R.id.edtPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)


        // 🔹 Autofill saved login details if available
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        edtUrl.setText(prefs.getString("baseUrl", ""))
        edtUser.setText(prefs.getString("username", ""))
        edtPass.setText(prefs.getString("password", ""))


        btnLogin.setOnClickListener {
            val baseUrl = edtUrl.text.toString().trim()
            val username = edtUser.text.toString().trim()
            val password = edtPass.text.toString().trim()

            //  Check network connectivity first

            if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this)) {
                Toast.makeText(this, "No network connection. Please check your network.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }


            if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!baseUrl.startsWith("https://")) {
                Toast.makeText(this, "Please enter a valid HTTPS URL", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Normalize baseUrl with triple slashes
            val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
                baseUrl.removeSuffix("/") + "///"
            } else {
                "$baseUrl///"
            }

            // Prepare query parameters (unencoded data)
            val rawData = "{\"username\":\"$username\",\"password\":\"$password\",\"authMethod\":\"online\"}"
            val rParam = "api/v1/Student/GetUserAuthenticatedData"

            // Log full URL (no hash in query)
            val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$rawData"
            Log.d(TAG, "REQUEST_URL: $fullUrl")
            Log.d(TAG, "REQUEST_PARAMS: r=$rParam, data=$rawData")
            Log.d(TAG, "REQUEST_HEADER: hash=$HASH")

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val hasInternet = withContext(Dispatchers.IO) { CheckNetworkAndInternetUtils.hasInternetAccess() }
                    if (!hasInternet) {
                        Toast.makeText(
                            this@LoginActivity,
                            "No internet access. Please check your connection.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }



                    val retrofit = ApiClient.getClient(baseUrl, HASH) // Pass hash for header
                    val service = retrofit.create(ApiService::class.java)

                    val response: Response<ResponseBody> = service.getUserAuthenticatedDataRaw(rParam, rawData)

                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful && response.body() != null) {
                            val rawString = response.body()!!.string()
                            Log.d(TAG, "FULL_RESPONSE: $rawString")  // Full raw JSON for verification

                            try {
                                val json = JSONObject(rawString)
                                val collection = json.optJSONObject("collection")
                                Log.d(TAG, "COLLECTION_RESPONSE: $collection")
                                if (collection == null) {
                                    Log.e(TAG, "PARSE_ERROR: Missing 'collection'")
                                    Toast.makeText(this@LoginActivity, "Parse Error: Invalid response structure", Toast.LENGTH_LONG).show()
                                    return@withContext
                                }

                                val responseObj = collection.optJSONObject("response")
                                Log.d(TAG, "RESPONSE: $responseObj")
                                if (responseObj == null) {
                                    Log.e(TAG, "PARSE_ERROR: Missing 'response'")
                                    Toast.makeText(this@LoginActivity, "Parse Error: Invalid response structure", Toast.LENGTH_LONG).show()
                                    return@withContext
                                }

                                // Log response fields for debugging
                                val responseStatus = responseObj.optString("statusMsg", "No statusMsg")
                                val responseMsg = responseObj.optString("message", "No message")
                                Log.d(TAG, "RESPONSE_FIELDS: status=$responseStatus, message=$responseMsg")

                                val userData = responseObj.optJSONObject("userData")
                                Log.d(TAG, "USERDATA: $userData")
                                if (userData == null) {
                                    Log.e(TAG, "PARSE_ERROR: Missing 'userData'. ResponseMsg: $responseMsg")
                                    Toast.makeText(this@LoginActivity, "Login failed: $responseMsg", Toast.LENGTH_LONG).show()
                                    return@withContext
                                }

                                // Safe access to fields
                                val isUserValid = userData.optString("isUserDataFound", "FALSE") == "TRUE"
                                val authMsg = userData.optString("authMsg", "No authMsg")

                                Log.d(TAG, "AUTH_STATUS: isUserValid=$isUserValid, authMsg=$authMsg")


                                // Collect schoolId and schoolShortName lists
                                // Collect unique schoolId and schoolShortName pairs
                                val schoolMap = mutableMapOf<String, String>() // schoolId to schoolShortName


                                val schoolsArray = userData.optJSONArray("schoolsData")
                                if (schoolsArray != null) {
                                    Log.d(TAG, "SCHOOLS_DATA_ARRAY: $schoolsArray")
                                    Log.d(TAG, "SCHOOLS_COUNT: ${schoolsArray.length()}")
                                    for (i in 0 until schoolsArray.length()) {
                                        val school = schoolsArray.optJSONObject(i)
                                        val syearsDataArray = school?.optJSONArray("syearsData")
                                        if (syearsDataArray != null) {
                                            for (j in 0 until syearsDataArray.length()) {
                                                val syearData = syearsDataArray.optJSONObject(j)
                                                val schoolId = syearData?.optString("schoolId", "No schoolId") ?: "No schoolId"
                                                val schoolShortName = syearData?.optString("schoolShortName", "No schoolShortName") ?: "No schoolShortName"
                                                Log.d(TAG, "SCHOOL[$i].SYEAR[$j]: schoolId=$schoolId, schoolShortName=$schoolShortName")
                                                if (!schoolMap.containsKey(schoolId)) {
                                                    schoolMap[schoolId] = schoolShortName
                                                }

                                            }
                                        } else {
                                            Log.d(TAG, "SCHOOL[$i].SYEARS_DATA: null")
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "SCHOOLS_DATA_ARRAY: null")
                                    Log.d(TAG, "SCHOOLS_COUNT: 0")
                                }

                                // Convert map to lists for Intent
                                val schoolIds = schoolMap.keys.toList()
                                val schoolShortNames = schoolMap.values.toList()

                                //check valid user or not
                                if (isUserValid) {

                                    //  Save login details for next launch
                                    prefs.edit()
                                        .putString("baseUrl", baseUrl)
                                        .putString("username", username)
                                        .putString("password", password)
                                        .putString("hash", HASH)
                                        .apply()

                                    Log.d(TAG, "SAVED_LOGIN_DETAILS: baseUrl=$baseUrl, username=$username, password=$password, hash=$HASH")


                                    // Navigate to SelectInstituteActivity with schoolIds and schoolShortNames
                                    val intent = Intent(
                                        this@LoginActivity,
                                        SelectInstituteActivity::class.java
                                    ).apply {
                                        putStringArrayListExtra("schoolIds", ArrayList(schoolIds))
                                        putStringArrayListExtra("schoolShortNames", ArrayList(schoolShortNames))
                                    }
                                    startActivity(intent)
                                    Log.d(TAG, "LOGIN_SUCCESS: Validation passed. Navigating to SelectInstituteActivity with schoolIds=$schoolIds, schoolShortNames=$schoolShortNames")
                                } else {
                                    Toast.makeText(this@LoginActivity, "Invalid username or password: $responseMsg", Toast.LENGTH_LONG).show()
                                    Log.e(TAG, "LOGIN_FAILED: $responseMsg")
                                }

                            } catch (parseE: Exception) {
                                Log.e(TAG, "JSON_PARSE_EXCEPTION: ${parseE.message}", parseE)
                                Toast.makeText(this@LoginActivity, "Parse Error: ${parseE.message}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val errorBody = response.errorBody()?.string() ?: "No error body"
                            Log.e(TAG, "API_ERROR: HTTP ${response.code()} - ${response.message()} - Error: $errorBody")
                            Toast.makeText(this@LoginActivity, "Server error: ${response.code()}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    Log.e(TAG, "API_EXCEPTION: ${e.message}", e)
                }
            }
        }
    }
}