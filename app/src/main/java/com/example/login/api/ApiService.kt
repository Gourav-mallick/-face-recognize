// api/ApiService.kt - Updated data classes based on full JSON structure for accurate parsing.
// Placeholder fields expanded to match the provided response. Gson will handle optional/null fields.

package com.example.login.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

data class ApiResponse(
    val collection: Collection
)

data class Collection(
    val version: String,
    val link: String,
    val response: ResponseObj
)

data class ResponseObj(
    val responseStatus: String,
    val responseMsg: String,
    val userData: UserData,
    val dataServiceStatus: String
)

data class UserData(
    val isUserDataFound: String,
    val failCount: String,
    val authMsg: String,
    val app_version: String,
    val schoolsData: List<SchoolData>,
    val classesData: List<ClassData>? = null,  // From the full response
    val childData: String,
    val userPhotoPath: String,
    val id: String,
    val regId: String,
    val firstName: String,
    val middleName: String,
    val lastName: String,
    val email: String,
    val mobile: String,
    val isAccountDisable: String,
    val class_id: String,
    val class_name: String,
    val userBaseProfileType: String,
    val userProfileId: String,
    val userProfileTitle: String,
    val mobileVerificationStatus: String,
    val emailVerificationStatus: String,
    val usrIds: String
)

data class SchoolData(
    val syearsData: List<SYearData>,
    val studentsData: String,
    val privileges: Boolean,
    val id: String,
    val longName: String,
    val shortName: String,
    val address: String,
    val city: String,
    val zipCode: String,
    val attendanceConfigType: String
)

data class SYearData(
    val userRegId: String,
    val userId: String,
    val schoolId: String,
    val schoolLongName: String,
    val schoolShortName: String,
    val syear: String,
    val longName: String,
    val shortName: String,
    val mpId: String,
    val startDate: String,
    val endDate: String,
    val termData: List<TermData>
)

data class TermData(
    val schoolId: String,
    val schoolLongName: String,
    val schoolShortName: String,
    val schoolSyear: String,
    val mpId: String,
    val title: String,
    val type: String,
    val startDate: String,
    val endDate: String
)

data class ClassData(
    val instId: String,
    val instShortName: String,
    val academicYear: String,
    val academicYearShortName: String,
    val mpId: String,
    val mpShortName: String,
    val mpStartDate: String,
    val mpEndDate: String,
    val classId: String,
    val classShortName: String,
    val classType: String
)

interface ApiService {
    @GET("sims-services/digitalsims/")
    suspend fun getUserAuthenticatedDataRaw(
        @Query("r") r: String,  //endpoint
        @Query("data") data: String
    ): Response<ResponseBody>


    // Fetch student list by passing JSON query
    @GET("sims-services/digitalsims/")
    suspend fun getStudents(
        @Query("r") r: String = "api/v1/StudentEnrollment/GetStudList",
        @Query("data") data: String
    ): Response<ResponseBody>

    @GET("sims-services/digitalsims/")
    suspend fun getTeachers(
        @Query("r") r: String = "api/v1/User/GetUserRegisteredDetails",
        @Query("data") data: String
    ): Response<ResponseBody>

    @GET("sims-services/digitalsims/")
    suspend fun getSubjectInstances(
        @Query("r") r: String = "api/v1/CoursePeriod/SubjectInstances",
        @Query("data") data: String
    ): Response<ResponseBody>




    @GET("sims-services/digitalsims/")
    suspend fun getDeveiceDataToserver(
        @Query("r") r: String = "api/v1/Hardware/DeviceUtilityMgmt",
        @Query("data") data: String
    ): Response<ResponseBody>


    @POST("sims-services/digitalsims/")
    suspend fun postAttendanceSync(
        @Query("r") r: String = "api/v1/Att/ManageMarkingGlobalAtt",
        @Body requestBody: RequestBody
    ): Response<ResponseBody>

}