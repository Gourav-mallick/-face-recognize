package com.example.login.view


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.login.R
import com.example.login.db.entity.Student
import androidx.activity.OnBackPressedCallback
import com.example.login.db.dao.AppDatabase
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch



class StudentScanFragment : Fragment() {

    private lateinit var tvPresentCount: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var tvTeacherName: TextView
    private lateinit var tvLastStudent: TextView
    private lateinit var tvLatestCardTapStudentLabel: TextView


    private var presentCount = 0

    companion object {
        private const val ARG_TEACHER = "arg_teacher"
        private const val ARG_SESSION_ID = "arg_session_id"

        fun newInstance(teacherName: String,sessionId: String) = StudentScanFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TEACHER, teacherName)
                putString(ARG_SESSION_ID, sessionId)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = inflater.inflate(R.layout.fragment_student_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvPresentCount = view.findViewById(R.id.tvPresentCount)
        tvInstruction = view.findViewById(R.id.tvInstruction)
        tvTeacherName = view.findViewById(R.id.tvTeacherName)
        tvLastStudent = view.findViewById(R.id.tvLastStudent)
        tvLatestCardTapStudentLabel= view.findViewById(R.id.tvLatestCardTapStudentLabel)

        tvInstruction.text = "Tap student card to mark present."

        val teacher = arguments?.getString(ARG_TEACHER) ?: "-"
        tvTeacherName.text = "$teacher"

        updatePresentCount()

        // 🔹 Disable back press (both button and gesture)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Toast.makeText(
                        requireContext(),
                        "Back is disabled on this screen",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        // 🔹 Save state that user is currently in StudentScanFragment
      //  saveResumeFlag(requireContext(), true)


        val sessionId = arguments?.getString(ARG_SESSION_ID)
        if (!sessionId.isNullOrEmpty()) {
            val db = AppDatabase.getDatabase(requireContext())

            lifecycleScope.launch {
                val count = db.attendanceDao().getAttendancesForSession(sessionId).size
                presentCount = count
                tvPresentCount.text = "$presentCount"
                tvTeacherName.text = "$teacher"


            }
        }
    }


    /**
     * Call this method when a student card is scanned.
     */
    fun addStudent(student: Student): Boolean {
        // Optional: prevent duplicate student scans
        // You can implement a Set or DB check if needed
        presentCount++
        tvPresentCount.text = "$presentCount"
        tvLastStudent.text = " ${student.studentName}"
        tvLatestCardTapStudentLabel.text = "Latest Card Tap"
        updateInstruction("Tap student card / Other Card")
        return true
    }

    fun updateInstruction(text: String) {
        tvInstruction.text = text
    }

    fun resetAttendance() {
        presentCount = 0
        tvPresentCount.text = "Present Students: 0"
        tvLastStudent.text = "-"
        tvInstruction.text = "Scan student/other card "
    }

    private fun updatePresentCount() {
        tvPresentCount.text = "$presentCount"
    }
    fun getStudentCount(): Int {
        return presentCount
    }




}
