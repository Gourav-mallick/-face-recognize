package com.example.login.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Entity(tableName = "students")
@Parcelize
data class Student(
    @PrimaryKey val studentId: String,
    val studentName: String,
    val classId: String,
    val instId: String, // store the institute ID
    val fingerType: String? = null,
    val embedding: String? = null // store the embedding as a string
) : Parcelable



@Entity(tableName = "teachers")
@Parcelize
data class Teacher(
    @PrimaryKey val staffId: String,
    val staffName: String,
    val instId: String, // store the institute ID
    val fingerType: String? = null,
    val embedding: String? = null
) : Parcelable


@Entity(tableName = "course_periods")
@Parcelize
data class CoursePeriod(
    @PrimaryKey val cpId: String,
    val courseId: String,
    val classId: String,
    val teacherId: String?,
    val mpId: String?, // term/period reference
    val mpLongTitle: String?,

    ): Parcelable


@Entity(tableName = "courses")
@Parcelize
data class Course(
    @PrimaryKey val courseId: String,
    val subjectId: String,
    val courseTitle: String,
    val courseShortName: String
) : Parcelable


@Entity(tableName = "subjects")
@Parcelize
data class Subject(
    @PrimaryKey val subjectId: String,
    val subjectTitle: String
) : Parcelable

@Entity(tableName = "classes")
@Parcelize
data class Class(
    @PrimaryKey val classId: String,
    val classShortName: String
) : Parcelable


@Entity(tableName = "teacher_class_map",
    primaryKeys = ["teacherId", "classId"])
data class TeacherClassMap(
    val teacherId: String,
    val classId: String
)


@Entity(tableName = "student_schedule")
data class StudentSchedule(
    @PrimaryKey val scheduleId: String,
    val studentId: String,
    val cpId: String,
    val courseId: String,
    val scheduleStartDate: String,
    val scheduleEndDate: String?,
    val syncStatus: String = "pending"
)


@Entity(tableName = "institutes")
data class Institute(
    @PrimaryKey val id: String,
    val shortName: String,
    val title: String?,
    val sYear: String?,
    val timezone: String?,
)



@Entity(tableName = "pending_scheduling")
data class PendingScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val school_id: String,
    val syear: String,
    val marking_period_id: String,
    val mp: String,

    val class_id: String,
    val class_title: String,

    val subjectId: String,
    val headId: String,

    val course_id: String,
    val course_period_id: String,
    val cp_title: String,

    val teacher_id: String,
    val teacher_name: String,

    val student_id: String,
    val student_name: String,

    val start_date: String,
    val created_by: String,

    val isCreateScheduling: String,
    val isUpdateScheduling: String,

    val syncStatus: String = "pending"
)


@Entity(tableName = "sessions")
@Parcelize
data class Session(
    @PrimaryKey
    val sessionId: String,
    val classId: String,
    val teacherId: String,
    val subjectId:  String,
  //  val headId: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val instId: String,
    val isMerged: Int,
    val periodId: String,
    val syncStatus:String,
    val isSubmitted: Int = 0
) : Parcelable


@Entity(tableName = "attendance")
@Parcelize
data class Attendance(
    @PrimaryKey
    val atteId: String,
    val instId: String,
    val instShortName: String? = null,
    val academicYear: String? = null,
    val classId: String,
    val markedAt: String,
    val sessionId: String,
    val status: String,
    val studentId: String,
    val studentName: String? = null,
    val syncStatus: String,
    val teacherId: String,
    val teacherName: String? = null,
    val date: String,
    val startTime: String,
    val endTime: String,
    val period: String,
    //  New fields for course/subject/class mapping
    val cpId: String? = null,               // Course Period ID
    val courseId: String? = null,           // Course ID
    val courseTitle: String? = null,        // Full course title
    val courseShortName: String? = null,    // Short name of course
    val subjectId: String? = null,          // Linked subject ID
    val subjectTitle: String? = null,       // Subject title
    val classShortName: String? = null,     // Human-readable class short name
    val mpId: String? = null,               // Master period ID / term ID
    val mpLongTitle: String? = null         // Master period long title
):Parcelable



// 🔹 Data class for joined info (not an @Entity)
data class CourseFullInfo(
    val cpId: String?,
    val courseId: String?,
    val courseTitle: String?,
    val courseShortName: String?,
    val subjectId: String?,
    val subjectTitle: String?,
    val classShortName: String?,
    val mpId: String?,
    val mpLongTitle: String?
)


// Data models for serialization
data class AttendancePayload(
    val attParamDataObj: AttendanceParamDataObj
)

data class AttendanceParamDataObj(
    val attDataArr: List<AttendanceData>,
    val attAttachmentArr: List<Any> = emptyList(),
    val attendanceMethod: String = "periodDayWiseAttendance",
    val loggedInUsrId: String
)

data class AttendanceData(
    val studentId: String,
    val instId: String,
    val instShortName: String,
    val academicYear: String,
    val academicYearShortName: String,
    val mpId: String,
    val mpShortName: String,
    val classId: String,
    val classShortName: String,
    val studentClass: String,
    val subjectId: String,
    val subjectShortName: String,
    val subjectCode: String,
    val courseId: String,
    val attCodetitle: String,
    val courseShortName: String,
    val courseSelectionMode: String,
    val cpId: String,
    val cpShortName: String,
    val stfId: String,
    val stfFML: String,
    val studId: String,
    val studfFML: String,
    val studfLFM: String,
    val studentName: String,
    val studAltId: String,
    val studRollNo: String,
    val int_rollNo: String,
    val attCycleId: String,
    val attSessionId: String,
    val attSchoolPeriodId: String,
    val attSchoolPeriodTitle: String,
    val attSchoolPeriodStartTime: String,
    val attSchoolPeriodEndTime: String,
    val attDate: String,
    val attSessionStartDateTime: String,
    val attSessionEndDateTime: String,
    val attCapturingIntervalDateTime: String,
    val attCapturingIntervalInSec: String,
    val attCapturingCycleState: String,
    val attCategory: String,
    val studAttComment: String,
    val attSessionStudId: String,
    val attCodeId: String,
    val attCodeLngName: String,
    val attCode: String,
    val studAttStartDateTime: String,
    val studAttEndDateTime: String,
    val studAttTotalDuration: String,
    val atsaId: String,
    val atsaIsProxy: String,
    val atsaDistanceDeltaInMeter: String,
    val isSelfUsrAttMarked: String,
    val attCoLectureCpIds: String,
    val toRemoveCoLecturerCpIds: String,
    val toAddCoLecturerCpIds: String,
    val status: String
)



@Entity(tableName = "ActiveClassCycle")
data class ActiveClassCycle(
    @PrimaryKey val classroomId: String,
    val classroomName: String,
    val teacherId: String?,
    val teacherName: String?,
    val sessionId: String?,
    val startedAtMillis: Long
    // Add presentCount or other fields if needed
)


object AttendanceIdGenerator {
    private var counter = 0
    fun nextId(): String {
        counter += 1
        return counter.toString()
    }
}


