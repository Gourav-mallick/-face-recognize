//check local database setup or not

package com.example.login.utility

import android.util.Log
import com.example.login.db.dao.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.collections.forEach

object LogDbDataToVerify {

    fun logAllData(
        studentsDao: StudentsDao,
        teachersDao: TeachersDao,
        classesDao: ClassDao,
        coursesDao: CourseDao,
        subjectsDao: SubjectDao,
        coursePeriodsDao: CoursePeriodDao,

    ) {
        CoroutineScope(Dispatchers.IO).launch {

            // Students
            val students = studentsDao.getAllStudents()
            if (students.isEmpty()) Log.d("RoomData", "No students found")
            else students.forEach { Log.d("RoomData", it.toString()) }

            //teacher
            val teachers=teachersDao.getAllTeachers()
            if (teachers.isEmpty()) Log.d("RoomData", "No teachers found")
            else teachers.forEach { Log.d("RoomData", it.toString()) }

            //course
            val courses=coursesDao.getAllCourses()
            if (courses.isEmpty()) Log.d("RoomData", "No courses found")
            else courses.forEach { Log.d("RoomData", it.toString()) }


            //subject
            val subjects=subjectsDao.getAllSubjects()
            if (subjects.isEmpty()) Log.d("RoomData", "No subjects found")
            else subjects.forEach { Log.d("RoomData", it.toString()) }

            //class
            val classes=classesDao.getAllClasses()
            if (classes.isEmpty()) Log.d("RoomData", "No classes found")
            else classes.forEach { Log.d("RoomData", it.toString()) }



            val coursePeriods=coursePeriodsDao.getAllCoursePeriods()
            if (coursePeriods.isEmpty()) Log.d("RoomData", "No coursePeriod found")
            else coursePeriods.forEach { Log.d("RoomData", it.toString()) }

            // Add other DAOs here similarly
        }
    }
}


