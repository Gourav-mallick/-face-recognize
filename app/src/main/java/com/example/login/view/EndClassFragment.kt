package com.example.login.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.login.R

class EndClassFragment : Fragment() {

    companion object {
        private const val ARG_TOTAL = "arg_total"
        fun newInstance(totalStudents: Int) = EndClassFragment().apply {
            arguments = Bundle().apply { putInt(ARG_TOTAL, totalStudents) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?)
            = inflater.inflate(R.layout.fragment_end_class, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvSummary = view.findViewById<TextView>(R.id.tvSummary)
        val btnDone = view.findViewById<Button>(R.id.btnDone)

        val total = arguments?.getInt(ARG_TOTAL) ?: 0
        tvSummary.text = "Class ended. Total present students: $total"

        btnDone.setOnClickListener {
            // Move user back to main screen (classroom scan)
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ClassroomScanFragment.newInstance(), "CLASSROOM")
                .commitAllowingStateLoss()
        }
    }
}
