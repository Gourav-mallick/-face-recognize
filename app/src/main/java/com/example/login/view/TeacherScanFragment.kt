package com.example.login.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.login.R

class TeacherScanFragment : Fragment() {

    private lateinit var tvHeader: TextView
    private lateinit var tvSyncStatus: TextView
   // private lateinit var tvTeacher: TextView
    private lateinit var tvClassCard: TextView


    companion object {
        private const val ARG_CLASSID  = "arg_classid"
        fun newInstance(classId: String) = TeacherScanFragment().apply {
            arguments = Bundle().apply { putString(ARG_CLASSID , classId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?)
            = inflater.inflate(R.layout.fragment_teacher_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
      //  tvHeader = view.findViewById(R.id.tvHeader)
       // tvSyncStatus = view.findViewById(R.id.tvSyncStatus)
      //  tvTeacher = view.findViewById(R.id.tvTeacher)
        tvClassCard = view.findViewById(R.id.tvClassCard)


      //  tvHeader.text = "Tap Staff card to continue..."
       // tvSyncStatus.text = "Sync Status : Please sync device."
        val classId = arguments?.getString(ARG_CLASSID ) ?: "-"
        tvClassCard.text = "Class-Room :  $classId"
      //  tvTeacher.text = "Teacher: - (Scan Teacher Card)"
    }
/*
    // optional helper to update teacher text if activity wants to call it:
    fun setTeacherName(name: String) {
        view?.findViewById<TextView>(R.id.tvTeacher)?.text = "Teacher: $name"
    }

 */
}
