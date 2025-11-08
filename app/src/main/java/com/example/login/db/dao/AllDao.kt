package com.example.login.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.login.db.entity.ActiveClassCycle
import com.example.login.db.entity.Attendance
import com.example.login.db.entity.Course
import com.example.login.db.entity.Student
import com.example.login.db.entity.Subject
import com.example.login.db.entity.Teacher
import com.example.login.db.entity.Class
import com.example.login.db.entity.CourseFullInfo
import com.example.login.db.entity.CoursePeriod
import com.example.login.db.entity.Session


@Dao
interface StudentsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(students: List<Student>)

    @Query("SELECT * FROM students WHERE instId = :instId")
    suspend fun getStudentsByInstitute(instId: String): List<Student>

    @Query("SELECT * FROM students WHERE studentId = :studentId")
    suspend fun getStudentById(studentId: String): Student

    @Query("SELECT * FROM students ")
    suspend fun getAllStudents(): List<Student>

    @Query("SELECT * FROM students WHERE classId = :classId")
    suspend fun getStudentsByClass(classId: String): List<Student>

    @Query("""
    SELECT * FROM students 
    WHERE studentId = :id
    AND (
        LOWER(studentName) LIKE '%' || LOWER(:namePart1) || '%' 
        OR LOWER(studentName) LIKE '%' || LOWER(:namePart2) || '%' 
        OR LOWER(studentName) LIKE '%' || LOWER(:namePart3) || '%'
    )
    LIMIT 1
""")
    suspend fun getAndMatchStudentByIdName(
        id: String,
        namePart1: String?,
        namePart2: String?,
        namePart3: String?
    ): Student?


}


@Dao
interface TeachersDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(teachers: List<Teacher>)

    @Query("SELECT * FROM teachers WHERE instId = :instId")
    suspend fun getTeachersByInstitute(instId: String): List<Teacher>

    @Query("SELECT * FROM teachers WHERE staffId = :teacherId")
    suspend fun getTeacherById(teacherId: String): Teacher?

    @Query("SELECT * FROM teachers")
    suspend fun getAllTeachers(): List<Teacher>

    @Query("SELECT staffName FROM teachers WHERE staffId = :teacherId")
    suspend fun getTeacherNameById(teacherId: String): String?

    @Query("""
    SELECT * FROM teachers 
    WHERE staffId = :id
    AND (
        LOWER(staffName) LIKE '%' || LOWER(:namePart1) || '%' 
        OR LOWER(staffName) LIKE '%' || LOWER(:namePart2) || '%' 
        OR LOWER(staffName) LIKE '%' || LOWER(:namePart3) || '%'
    )
    LIMIT 1
""")
    suspend fun getAndMatchTeacherByIdName(
        id: String,
        namePart1: String?,
        namePart2: String?,
        namePart3: String?
    ): Teacher?


}


@Dao
interface SubjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subjects: List<Subject>)

    @Query("SELECT * FROM subjects")
    suspend fun getAllSubjects(): List<Subject>
}

@Dao
interface CourseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<Course>)

    @Query("SELECT * FROM courses")
    suspend fun getAllCourses(): List<Course>


    // 🔹 Full joined course details
    @Query("""
        SELECT 
            cp.cpId AS cpId,
            c.courseId AS courseId,
            c.courseTitle AS courseTitle,
            c.courseShortName AS courseShortName,
            s.subjectId AS subjectId,
            s.subjectTitle AS subjectTitle,
            cls.classShortName AS classShortName,
            cp.mpId AS mpId,
            cp.mpLongTitle AS mpLongTitle
        FROM courses c
        LEFT JOIN subjects s ON s.subjectId = c.subjectId
        LEFT JOIN course_periods cp ON cp.courseId = c.courseId
        LEFT JOIN classes cls ON cls.classId = cp.classId
        WHERE c.courseId IN (:courseIds)
    """)
    suspend fun getCourseDetailsForIds(courseIds: List<String>): List<CourseFullInfo>

}



@Dao
interface ClassDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(classes: List<Class>)

    @Query("SELECT * FROM classes WHERE classId = :classId")
    suspend fun getClassById(classId: String): Class?


    @Query("SELECT * FROM classes")
    suspend fun getAllClasses(): List<Class>


}



@Dao
interface CoursePeriodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(periods: List<CoursePeriod>)

    @Query("SELECT * FROM course_periods")
    suspend fun getAllCoursePeriods(): List<CoursePeriod>
}


@Dao
interface SessionDao {

    // Insert new session
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: Session)

    // Optional: Get all sessions
    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<Session>

    @Query("UPDATE sessions SET endTime = :endTime WHERE sessionId = :sessionId")
    suspend fun updateSessionEnd(sessionId: String,  endTime: String)

    // Optional: Get current session by ID
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): Session?


    @Query("UPDATE sessions SET  subjectId = :subjectIds WHERE sessionId = :sessionId")
    suspend fun updateSessionPeriodAndSubject(
        sessionId: String,

        subjectIds: String
    )

    @Query("UPDATE sessions SET classId = :classIds WHERE sessionId = :sessionId")
    suspend fun updateSessionClasses(sessionId: String, classIds: String)


    @Query("DELETE FROM Sessions WHERE syncStatus = 'complete'")
    suspend fun deleteSyncedSessions(): Int

    @Query("UPDATE sessions SET syncStatus = :newStatus WHERE sessionId = :sessionId")
    suspend fun updateSessionSyncStatusToComplete(sessionId: String, newStatus: String)


}


@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(attendance: Attendance): Long

    // Get all attendance records
    @Query("SELECT * FROM attendance")
    suspend fun getAllAttendance(): List<Attendance>

    @Query("SELECT DISTINCT classId FROM attendance WHERE sessionId = :sessionId")
    suspend fun getDistinctClassIdsForCurrentSession(sessionId: String): List<String>

    @Query("SELECT * FROM attendance WHERE sessionId = :sessionId AND classId = :classId")
    suspend fun getAttendancesForClass(sessionId: String, classId: String): List<Attendance>

    @Query("SELECT * FROM attendance WHERE sessionId = :sessionId AND teacherId = :teacherId")
    suspend fun getAttendancesForTeacherId(sessionId: String, teacherId: String): List<Attendance>


    @Query("UPDATE attendance SET endTime = :endTime WHERE sessionId = :sessionId")
    suspend fun updateAttendanceEndTime(sessionId: String,  endTime: String)

    @Query("DELETE FROM attendance WHERE sessionId = :sessionId AND classId = :classId")
    suspend fun deleteAttendanceForClass(sessionId: String, classId: String)


    @Query("DELETE FROM attendance WHERE sessionId = :sessionId AND classId NOT IN (:selectedClassIds)")
    suspend fun deleteAttendanceNotInClasses(selectedClassIds: List<String>, sessionId: String)


    @Query("SELECT * FROM attendance WHERE sessionId = :sessionId AND studentId = :studentId LIMIT 1")
    suspend fun getAttendanceForStudentInSession(sessionId: String, studentId: String): Attendance?


    @Query("""
    SELECT s.* FROM attendance a
    INNER JOIN students s ON a.studentId = s.studentId
    WHERE a.sessionId = :sessionId AND a.classId = :classId
""")
    suspend fun getStudentsForClassInSession(sessionId: String, classId: String): List<Student>


    @Query("""
        UPDATE attendance
        SET 
            cpId = :cpId,
            courseId = :courseId,
            courseTitle = :courseTitle,
            courseShortName = :courseShortName,
            subjectId = :subjectId,
            subjectTitle = :subjectTitle,
            classShortName = :classShortName,
            mpId = :mpId,
            mpLongTitle = :mpLongTitle

        WHERE sessionId = :sessionId
    """)
    suspend fun updateAttendanceWithCourseDetails(
        sessionId: String,
        cpId: String?,
        courseId: String?,
        courseTitle: String?,
        courseShortName: String?,
        subjectId: String?,
        subjectTitle: String?,
        classShortName: String?,
        mpId: String?,
        mpLongTitle: String?
    )


    @Query("SELECT * FROM attendance WHERE syncStatus = 'pending'")
    suspend fun getPendingAttendances(): List<Attendance>

    @Query("UPDATE attendance SET syncStatus = :newStatus WHERE atteId = :atteId")
    suspend fun updateSyncStatus(atteId: String, newStatus: String)
    // ✅ Get all attendance for a specific session
    @Query("SELECT * FROM attendance WHERE sessionId = :sessionId")
    suspend fun getAttendanceBySessionId(sessionId: String): List<Attendance>

    // ✅ Update sync status for all attendance in a session
    @Query("UPDATE attendance SET syncStatus = :newStatus WHERE sessionId = :sessionId")
    suspend fun updateSyncStatusBySession(sessionId: String, newStatus: String)



    @Query("SELECT * FROM Attendance WHERE sessionId = :sessionId")
    suspend fun getAttendancesForSession(sessionId: String): List<Attendance>


    @Query("DELETE FROM Attendance WHERE syncStatus = 'complete'")
    suspend fun deleteSyncedAttendances(): Int


    @Query("""
    SELECT COUNT(*) FROM Attendance 
    WHERE studentId = :studentId
    AND sessionId IN (
        SELECT sessionId FROM sessions
        WHERE (endTime IS NULL OR endTime = '') AND sessionId != :currentSessionId
    )
""")
    suspend fun countActiveAttendancesForStudent(studentId: String, currentSessionId: String): Int


    @Query("SELECT * FROM attendance WHERE syncStatus = :status")
    suspend fun getPendingAttendancesByStatus(status: String): List<Attendance>



}



@Dao
interface ActiveClassCycleDao {
    @Query("SELECT * FROM ActiveClassCycle")
    suspend fun getAll(): List<ActiveClassCycle>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cycle: ActiveClassCycle)

    @Delete
    suspend fun delete(cycle: ActiveClassCycle)

    @Query("DELETE FROM ActiveClassCycle WHERE classroomId = :classroomId")
    suspend fun deleteByClassroomId(classroomId: String)
}
