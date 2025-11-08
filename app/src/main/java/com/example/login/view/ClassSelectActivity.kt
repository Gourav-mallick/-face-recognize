package com.example.login.view

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.login.databinding.ActivityClassSelectBinding
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.launch

class ClassSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityClassSelectBinding
    private lateinit var db: AppDatabase
    private lateinit var sessionId: String
    private val selectedClassIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClassSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        sessionId = intent.getStringExtra("SESSION_ID") ?: return



        // 🔹 Disable back button + back gesture using OnBackPressedDispatcher
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // This is called when the user presses the back button or swipes back
                Toast.makeText(this@ClassSelectActivity, "Back disabled on this screen", Toast.LENGTH_SHORT).show()

            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)


        lifecycleScope.launch {

            val preSelected = db.attendanceDao().getDistinctClassIdsForCurrentSession(sessionId)
            val allClasses = db.classDao().getAllClasses()

            // 🔹 Filter only preselected classes
            val preSelectedClasses = allClasses.filter { preSelected.contains(it.classId) }

            selectedClassIds.addAll(preSelected)

            val adapter = ClassSelectAdapter(preSelectedClasses, preSelected) { classId, isChecked, wasPreSelected ->
                handleClassSelectionChange(classId, isChecked, wasPreSelected)
            }

            binding.recyclerViewClasses.layoutManager = LinearLayoutManager(this@ClassSelectActivity)
            binding.recyclerViewClasses.adapter = adapter
        }

        binding.btnContinue.setOnClickListener {
            if (selectedClassIds.isEmpty()) {
                Toast.makeText(this, "Please select at least one class", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                // 🔹 Delete attendance of all unselected classes
                db.attendanceDao().deleteAttendanceNotInClasses(selectedClassIds.toList(), sessionId)

                // 🔹 Update session with selected class IDs
                db.sessionDao().updateSessionClasses(sessionId, selectedClassIds.joinToString(","))

                Toast.makeText(this@ClassSelectActivity, "Classes updated successfully", Toast.LENGTH_SHORT).show()


                // 🔹 When continuing, clear resume flag so app won’t reopen here again
                getSharedPreferences("APP_STATE", MODE_PRIVATE)
                    .edit()
                    .remove("IS_IN_CLASS_SELECT")
                    .remove("SESSION_ID")
                    .apply()

                // 🔹 Navigate next
                val intent = Intent(this@ClassSelectActivity, SubjectSelectActivity::class.java)
                intent.putExtra("SESSION_ID", sessionId)
                intent.putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClassIds))
                startActivity(intent)
                finish()
            }
        }
    }




    // 🔹 Save app state so when reopened, it resumes here
    override fun onPause() {
        super.onPause()
        getSharedPreferences("APP_STATE", MODE_PRIVATE)
            .edit()
            .putBoolean("IS_IN_CLASS_SELECT", true)
            .putString("SESSION_ID", sessionId)
            .apply()
    }

    private fun handleClassSelectionChange(classId: String, isChecked: Boolean, wasPreSelected: Boolean) {
        lifecycleScope.launch {
            if (!isChecked && wasPreSelected) {
                // Class was preselected → warn teacher before removing
                val students = db.attendanceDao().getStudentsForClassInSession(sessionId, classId)

                if (students.isNotEmpty()) {
                  //  val studentListText = students.joinToString("\n") { "${it.studentId} - ${it.studentName}" }

                    runOnUiThread {
                        AlertDialog.Builder(this@ClassSelectActivity)
                            .setTitle("Remove Attandance")
                            .setMessage(
                                "These students belong to this class:\nNo. of Students -\n${students.size} \n\n" +
                                        "Their attendance will be deleted. Continue?"
                            )
                            .setPositiveButton("Yes") { _, _ ->
                                lifecycleScope.launch {
                                    db.attendanceDao().deleteAttendanceForClass(sessionId, classId)
                                    selectedClassIds.remove(classId)
                                    Toast.makeText(
                                        this@ClassSelectActivity,
                                        "Attendance removed for ${students.size} student(s)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .setNegativeButton("No") { dialog, _ ->
                                dialog.dismiss()
                                selectedClassIds.add(classId) // keep it checked again
                                recreate() // refresh UI
                            }
                            .show()
                    }
                } else {
                    // no students found, just remove silently
                    selectedClassIds.remove(classId)
                }
            } else if (isChecked) {
                selectedClassIds.add(classId)
            }
        }
    }
}
