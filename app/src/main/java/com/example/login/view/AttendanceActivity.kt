package com.example.login.view

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Attendance
import com.example.login.db.entity.AttendanceIdGenerator
import com.example.login.db.entity.Session
import com.example.login.db.entity.Student
import kotlinx.coroutines.launch
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.String
import kotlin.concurrent.thread
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.login.utility.AutoSyncWorker
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.example.login.db.entity.ActiveClassCycle

class AttendanceActivity : AppCompatActivity() {

    private  var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private val TAG = "NFC_DEBUG"


    companion object {
        private const val TAG_CLASSROOM = "CLASSROOM"
        private const val TAG_TEACHER = "TEACHER"
        private const val TAG_STUDENT = "STUDENT"
    }


    data class AttendanceCycle(
        val classroomId: String,
        var classroomName: String,
        var teacherId: String? = null,
        var teacherName: String? = null,
        var sessionId: String? = null,
        var startedAtMillis: Long = System.currentTimeMillis()
    )

    private val activeSessions = mutableMapOf<Pair<String, String>, AttendanceCycle>()

    //  Track which teacher is currently active for student scans
    private var currentTeacherId: String? = null
    private var currentVisibleClassroomId: String? = null
    private var isTimeValid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)
        //if app exit then restore last cycle
        restoreLastCycleIfExists()

        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "HourlySync", ExistingPeriodicWorkPolicy.KEEP, request
        )

        // Check device time and match with server time
        checkDeviceTime { startClassroomScanFragment() }

        if (savedInstanceState == null) {
            val frag = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag, TAG_CLASSROOM)
                .commit()
        }

        // Restore pending sessions (endTime empty) into activeClasses
        restorePendingSessions()
    }

    override fun onResume() {
        super.onResume()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        try { nfcAdapter?.disableForegroundDispatch(this) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val tagId = tag.id.joinToString(":") { String.format("%02X", it) }
        Log.d(TAG, "Tag UID: $tagId")
        readCustomCardData(tag)
    }

    // ---------------- NFC reading  ----------------
    private fun readCustomCardData(tag: Tag) {
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val keyHex = prefs.getString("cpass", null)
        if (keyHex.isNullOrEmpty()) {
            Toast.makeText(this, "Missing authentication key (cpass)!", Toast.LENGTH_LONG).show()
            Log.e(TAG, "No key found in SharedPreferences.")
            return
        }

        val keyBytes = try { hexStringToByteArray(keyHex) } catch (e: Exception) {
            Toast.makeText(this, "Invalid hex key format!", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Key conversion failed: ${e.message}")
            return
        }
        Log.d(TAG, "Key bytes: ${keyBytes.joinToString(" ")}")
        Log.d(TAG, "Key bytes actual data: ${keyBytes.toString()}")



        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            Toast.makeText(this, "Not a MIFARE Classic card", Toast.LENGTH_LONG).show()
            Log.e(TAG, "MifareClassic not supported on this tag")
            return
        }

        try {
            mifare.connect()
            val sectorIndex = 0
            val auth = mifare.authenticateSectorWithKeyA(sectorIndex, keyBytes)
            if (!auth) {
                Toast.makeText(this, "Authentication failed!", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Authentication failed.")
                return
            }

            val firstBlock = mifare.sectorToBlock(sectorIndex)
            val blockCount = mifare.getBlockCountInSector(sectorIndex)
            var name: String? = null

            for (i in 0 until blockCount) {
                val blockIndex = firstBlock + i
                if (blockIndex == 0 || (blockIndex + 1) % 5 == 0) continue
                val data = mifare.readBlock(blockIndex)
                val rawText = String(data, Charsets.ISO_8859_1).replace("\u0000", "").trim()
                if (blockIndex == firstBlock + 1) {
                    name = rawText
                    Log.d(TAG, "Name: $rawText")
                }
                if (blockIndex == firstBlock + 2) {
                    val facilityCode = data.sliceArray(0..1).joinToString("") { "%02X".format(it) }
                    val idTypeHex = data.sliceArray(2..4).joinToString("") { "%02X".format(it) }
                    Log.d(TAG, "Block2 raw bytes hex: $idTypeHex")
                    val firstChar = idTypeHex.firstOrNull()?.uppercaseChar()
                    var typeChar: String? = null
                    var idValue = ""

                    if (firstChar != null && (firstChar == 'A' || firstChar == 'B' || firstChar == 'C')) {
                        typeChar = firstChar.toString()
                        idValue = idTypeHex.substring(1).trimStart('0').ifEmpty { "0" }
                    } else {
                        val slice = data.sliceArray(2..4)
                        val idBytes = ArrayList<Byte>()
                        for (b in slice) {
                            when (b.toInt() and 0xFF) {
                                0x41 -> typeChar = "A"
                                0x42 -> typeChar = "B"
                                0x43 -> typeChar = "C"
                                0x00 -> { }
                                else -> idBytes.add(b)
                            }
                        }
                        idValue = if (idBytes.isEmpty()) "" else idBytes.joinToString("") { "%02X".format(it) }.trimStart('0').ifEmpty { "0" }
                    }

                    if (typeChar == null) typeChar = "Unknown"
                    Log.d(TAG, "Parsed -> Facility: $facilityCode | Type: $typeChar | ID: $idValue")

                    handleNfcScan(name ?: "-", typeChar, idValue)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error reading card: ${e.message}", e)
            Toast.makeText(this, "Error reading card: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            try { mifare.close() } catch (_: Exception) {}
            Log.d(TAG, "Card connection closed.")
        }
    }

    // ---------------- High-level NFC handling ----------------
    private fun handleNfcScan(name: String, typeChar: String, idValue: String) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val nameParts = name.trim().split(" ").filter { it.isNotEmpty() }
            val part1 = nameParts.getOrNull(0)
            val part2 = nameParts.getOrNull(1)
            val part3 = nameParts.getOrNull(2)

            when (typeChar) {
                "A" -> {
                    val classObj = db.classDao().getClassById(idValue)
                    if (classObj != null) handleClassScan(classObj.classId, classObj.classShortName)
                    else Toast.makeText(this@AttendanceActivity, "Class not found!", Toast.LENGTH_SHORT).show()
                }
                "B" -> {
                    val teacher = db.teachersDao().getAndMatchTeacherByIdName(idValue, part1, part2, part3)
                    if (teacher != null) handleTeacherScan(teacher.staffId, teacher.staffName)
                    else Toast.makeText(this@AttendanceActivity, "Teacher not found!", Toast.LENGTH_SHORT).show()
                }
                "C" -> {
                    val student = db.studentsDao().getAndMatchStudentByIdName(idValue, part1, part2, part3)
                    if (student != null) handleStudentScan(student)
                    else Toast.makeText(this@AttendanceActivity, "Student not found!", Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(this@AttendanceActivity, "Unknown type: $typeChar", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // ----------------- Scan: Classroom -----------------
    private fun handleClassScan(classroomId: String, classroomName: String) {
        lifecycleScope.launch {
            // 🔹 Check if this classroom already has any active sessions (any teacher)
            val existingForClass = activeSessions.keys.any { it.first == classroomId }

            if (!existingForClass) {
                // ✅ Start a new classroom cycle (no active session yet)
                val cycle = AttendanceCycle(classroomId, classroomName)
                // Note: No teacher yet, teacher will scan next.
                currentVisibleClassroomId = classroomId

                Toast.makeText(
                    this@AttendanceActivity,
                    "Classroom ready: Please scan teacher card.",
                    Toast.LENGTH_SHORT
                ).show()

                // Go to teacher scan fragment
                val frag = TeacherScanFragment.newInstance(classroomId)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag, TAG_TEACHER)
                    .commitAllowingStateLoss()
            } else {

                // classroom card not for close we comment this part

       /*
                // 🔹 A class already exists in this classroom

                val activeForThisClass = activeSessions.filterKeys { it.first == classroomId }

                // If currently visible classroom is the same → ask to close the visible teacher’s class
                if (currentVisibleClassroomId == classroomId && currentTeacherId != null) {
                    showEndClassDialog(classroomId)
                    return@launch
                }

                // Otherwise, resume the first teacher’s active session
                val firstCycle = activeForThisClass.values.firstOrNull()
                if (firstCycle != null) {
                    currentVisibleClassroomId = classroomId
                    currentTeacherId = firstCycle.teacherId

                    Toast.makeText(
                        this@AttendanceActivity,
                        "Resuming ${firstCycle.classroomName} (Teacher: ${firstCycle.teacherName})",
                        Toast.LENGTH_SHORT
                    ).show()

                    val frag = StudentScanFragment.newInstance(firstCycle.teacherName ?: "")
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag, TAG_STUDENT)
                        .commitAllowingStateLoss()
                } else {
                    // If somehow no session found, show teacher scan again
                    val frag = TeacherScanFragment.newInstance(classroomId)
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag, TAG_TEACHER)
                        .commitAllowingStateLoss()
                }

       */
                // Just show info that classroom is already active

                Toast.makeText(
                    this@AttendanceActivity,
                    "Classroom $classroomName already assigned. Scan teacher card to continue.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }



// ----------------- Scan: Teacher -----------------
private fun handleTeacherScan(teacherId: String, teacherName: String) {
    lifecycleScope.launch {

        // Make sure a classroom is active first
        val classroomId = currentVisibleClassroomId
        if (classroomId.isNullOrEmpty()) {
            //  Show popup with teacher details
            val dialog = AlertDialog.Builder(this@AttendanceActivity)
                .setTitle("Teacher Card Details")
                .setMessage(
                            "NAME : $teacherName\n" +
                            "ID : $teacherId\n"
                )
                .setCancelable(false)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .create()
            dialog.show()

            // 🕒 Auto-dismiss after 3 seconds (3000 ms)
            Handler(Looper.getMainLooper()).postDelayed({
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }, 2000)

            Toast.makeText(
                this@AttendanceActivity,
                "Please scan a classroom card first.",
                Toast.LENGTH_SHORT
            ).show()
            return@launch
        }

        val db = AppDatabase.getDatabase(this@AttendanceActivity)
        val key = Pair(classroomId, teacherId)

        // Check if this teacher already has an active session
        val activeCycle = activeSessions[key]
        val existingDbSession = getActiveSession(classroomId, teacherId)

        //  CASE 1: Teacher already has active session in memory or DB
        if (activeCycle != null || (existingDbSession != null && !existingDbSession.sessionId.isNullOrEmpty())) {
            // If teacher is currently visible → ask to close
            val resumedSessionId = activeCycle?.sessionId ?: existingDbSession?.sessionId ?: ""

            if (currentTeacherId == teacherId) {
                showEndClassDialog(classroomId)  // ask to close
                return@launch
            } else {
                // Resume their own paused session
                currentTeacherId = teacherId
                Toast.makeText(this@AttendanceActivity, "Resuming your session...", Toast.LENGTH_SHORT).show()
                val frag = StudentScanFragment.newInstance(teacherName,resumedSessionId)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag, TAG_STUDENT)
                    .commitAllowingStateLoss()
                return@launch
            }
        }

        //  CASE 2: No active session yet — start new one

        val isSwitchingTeacher = !currentTeacherId.isNullOrEmpty() && currentTeacherId != teacherId

        if (isSwitchingTeacher) {
            AlertDialog.Builder(this@AttendanceActivity)
                .setTitle("New Session Detected")
                .setMessage("Session has been started By\n $teacherName")
                .setCancelable(false)
                .setPositiveButton("Yes") { dialog, _ ->
                    dialog.dismiss()
                    startNewTeacherSession(teacherId, teacherName, classroomId)
                }
                .setNegativeButton("No") { dialog, _ ->
                    //  Just close dialog and stay on current activity
                    dialog.dismiss()
                }
                .show()
        } else {
            // First teacher — start directly without popup
            startNewTeacherSession(teacherId, teacherName, classroomId)
        }

    }
}

    private fun startNewTeacherSession(teacherId: String, teacherName: String, classId: String) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val sessionId = UUID.randomUUID().toString()
            val estimated = getEstimatedCurrentTime()
            val startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(estimated)
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(estimated)
            val instId = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                .getString("selectedInstituteIds", "") ?: ""

            val session = Session(
                sessionId = sessionId,
                classId = classId,
                teacherId = teacherId,
                subjectId = "",
                date = date,
                startTime = startTime,
                endTime = "",
                isMerged = 0,
                instId = instId,
                syncStatus = "pending",
                periodId = ""
            )
            db.sessionDao().insertSession(session)

            val newCycle = AttendanceCycle(
                classroomId = classId,
                classroomName = classId,
                teacherId = teacherId,
                teacherName = teacherName,
                sessionId = sessionId
            )

            activeSessions[Pair(classId, teacherId)] = newCycle
            saveActiveSession(newCycle)
            currentTeacherId = teacherId

            val frag = StudentScanFragment.newInstance(teacherName, sessionId)
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag, TAG_STUDENT)
                .commitAllowingStateLoss()
        }
    }



    // ----------------- Scan: Student -----------------
    private fun handleStudentScan(student: Student) {
        lifecycleScope.launch {

            // ✅ If no classroom started yet show student card details
            if (currentVisibleClassroomId == null) {

                val dialog =AlertDialog.Builder(this@AttendanceActivity)
                    .setTitle("Students Card Details")
                    .setMessage(
                        "NAME : ${student.studentName}\n" +
                        "ID   : ${student.studentId}\n"
                    )
                    .setCancelable(false)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()
                dialog.show()

                // 🕒 Auto-dismiss after 3 seconds
                Handler(Looper.getMainLooper()).postDelayed({
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                }, 2000)

                Toast.makeText(
                    this@AttendanceActivity,
                    "Please scan classroom card Or Teacher card first.",
                    Toast.LENGTH_LONG
                ).show()

                Log.d(TAG, "Student scanned without classroom: ID=${student.studentId}, Name=${student.studentName}")
                return@launch
            }

            val classroomId = currentVisibleClassroomId ?: return@launch
            val teacherId = currentTeacherId ?: run {
                Toast.makeText(this@AttendanceActivity, "Please scan teacher card first!", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val cycle = activeSessions[Pair(classroomId, teacherId)] ?: run {
                Toast.makeText(this@AttendanceActivity, "No active session found for this teacher!", Toast.LENGTH_SHORT).show()
                return@launch
            }


            if (cycle.sessionId.isNullOrEmpty()) {
                Toast.makeText(this@AttendanceActivity, "Scan teacher first!", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            // 🔹 Already marked in this session?
            val existing = db.attendanceDao().getAttendanceForStudentInSession(cycle.sessionId!!, student.studentId)
            if (existing != null) {
                Toast.makeText(this@AttendanceActivity, "${student.studentName} already marked!", Toast.LENGTH_SHORT).show()
                return@launch
            }


            // 🔹  Prevent student from joining another open session
            val currentSessionId = cycle.sessionId!!
            val alreadyActive = db.attendanceDao()
                .countActiveAttendancesForStudent(student.studentId, currentSessionId)

            if (alreadyActive > 0) {
                Toast.makeText(this@AttendanceActivity, "You are already marked present in another ongoing class.", Toast.LENGTH_LONG).show()
                return@launch
            }




            val estimated = getEstimatedCurrentTime()
            val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(estimated)
            val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(estimated)
            val startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(estimated)

            val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
            val savedInstituteId = prefs.getString("selectedInstituteIds", "")
            val savedInstituteName = prefs.getString("selectedInstituteNames", "")


            val attendance = Attendance(
                atteId = AttendanceIdGenerator.nextId(),
                sessionId = cycle.sessionId!!,
                studentId = student.studentId,
                studentName = student.studentName,
                classId = student.classId,
                status = "P",
                markedAt = timeStamp,
                syncStatus = "pending",
                instId = savedInstituteId!!,
                instShortName = savedInstituteName,
                date = currentDate,
                startTime = startTime,
                endTime = "",
                academicYear = "",
                period = "",
                teacherId =cycle.teacherId!!,
                teacherName = cycle.teacherName!!,
            )


            Log.d("SYNC_DEBUG_attandance", "Attendance: $attendance")
            db.attendanceDao().insertAttendance(attendance)
            saveCurrentCycle()
            val frag = supportFragmentManager.findFragmentByTag(TAG_STUDENT)
            if (frag is StudentScanFragment) frag.addStudent(student)
        }
    }

    // ---------------- End / Submit class ----------------
    private fun showEndClassDialogForVisibleClass() {
        val classroomId = currentVisibleClassroomId ?: return
        showEndClassDialog(classroomId)
    }

    // ----------------- End Class -----------------
    private fun showEndClassDialog(classroomId: String) {
        val teacherId = currentTeacherId ?: return
        val cycle = activeSessions[Pair(classroomId, teacherId)] ?: return
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val presentCount = if (!cycle.sessionId.isNullOrEmpty())
                db.attendanceDao().getAttendancesForClass(cycle.sessionId!!, classroomId).size else 0

            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(getEstimatedCurrentTime())

            AlertDialog.Builder(this@AttendanceActivity)
                .setTitle("Close Class: ${cycle.classroomId}")
                .setMessage("Do you want to close this class?")
                .setPositiveButton("Yes") { _, _ ->
                    lifecycleScope.launch {
                        cycle.sessionId?.let { db.sessionDao().updateSessionEnd(it, currentTime) }
                        cycle.sessionId?.let{db.attendanceDao().updateAttendanceEndTime(it, currentTime)}

                        // remove from ActiveClassCycle table
                        removeActiveSession(classroomId, teacherId)
                        activeSessions.remove(Pair(classroomId, teacherId))


                        val broadcastIntent  = Intent("UPDATE_UNSUBMITTED_COUNT")
                        sendBroadcast(broadcastIntent )




                        Log.d("SESSION_END", "Session ${cycle.sessionId} closed at $currentTime")

                        val intent = Intent(this@AttendanceActivity, ClassSelectActivity::class.java)
                        intent.putExtra("SESSION_ID", cycle.sessionId)
                        intent.putExtra("TEACHER_ID", cycle.teacherId)
                        startActivity(intent)
                        activeSessions.remove(Pair(classroomId, teacherId))

                        currentVisibleClassroomId = null
                    }
                }
                .setNegativeButton("No", null)
                .show()

            val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
            prefs.edit().clear().apply()

        }
    }

    // ---------------- Utilities ----------------
    private fun startClassroomScanFragment() {
        if (supportFragmentManager.findFragmentByTag(TAG_CLASSROOM) == null) {
            val fragment = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment, TAG_CLASSROOM)
                .commit()
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        require(len % 2 == 0) { "Hex string must have even length" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun checkDeviceTime(onChecked: (() -> Unit)? = null) {
        if (!isNetworkAvailable()) {
            onChecked?.invoke() // skip check if offline
            return
        }

        thread {
            try {
                val connection = URL("https://google.com").openConnection()
                connection.connect()
                val networkTime = Date(connection.date)
                val deviceTime = Date(System.currentTimeMillis())

                val difference = kotlin.math.abs(deviceTime.time - networkTime.time)
                val diffMinutes = difference / (1000 * 60)

                Log.d("TIME_CHECK", "Device Time: $deviceTime")
                Log.d("TIME_CHECK", "Network Time: $networkTime")
                Log.d("TIME_CHECK", "Difference (minutes): $diffMinutes")

                if (diffMinutes > 2) {
                    isTimeValid = false
                    runOnUiThread { showTimeMismatchDialog() }
                } else {
                    isTimeValid = true
                    runOnUiThread { onChecked?.invoke() }
                }
            } catch (e: Exception) {
                Log.e("TIME_CHECK", "Error checking time: ${e.message}")
                isTimeValid = true
                runOnUiThread { onChecked?.invoke() }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetworkInfo
        return net != null && net.isConnected
    }


    private fun showTimeMismatchDialog() {
        AlertDialog.Builder(this)
            .setTitle("Incorrect Time Detected")
            .setMessage("Your device time doesn't match the actual time.\nPlease correct it before proceeding.")
            .setCancelable(false)
            .setPositiveButton("Set Time") { _, _ ->
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                finish()
            }
            .show()
    }


    private fun saveCurrentCycle() {
        val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
        val editor = prefs.edit()

        val classroomId = currentVisibleClassroomId
        val teacherId = currentTeacherId

        if (classroomId != null && teacherId != null) {
            val cycle = activeSessions[Pair(classroomId, teacherId)]
            if (cycle != null) {
                editor.putString("classroomId", cycle.classroomId)
                editor.putString("classroomName", cycle.classroomName)
                editor.putString("teacherId", cycle.teacherId)
                editor.putString("teacherName", cycle.teacherName)
                editor.putString("sessionId", cycle.sessionId)
                editor.apply()
                Log.d(TAG, "Saved current cycle: class=$classroomId teacher=$teacherId session=${cycle.sessionId}")
            }
        } else {
            Log.d(TAG, "No active classroom/teacher to save.")
        }
    }


    private fun restoreLastCycleIfExists() {
        val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
        val classroomId = prefs.getString("classroomId", null)
        val sessionId = prefs.getString("sessionId", null)
        val className = prefs.getString("classroomName", null)
        val teacherId = prefs.getString("teacherId", null)
        val teacherName = prefs.getString("teacherName", null)

        // 🔹 Check if valid data exists
        if (classroomId.isNullOrEmpty() || sessionId.isNullOrEmpty() || teacherId.isNullOrEmpty()) {
            Log.d(TAG, "No previous attendance cycle found to restore.")
            return
        }

        // 🔹 Recreate the previous attendance cycle object
        val cycle = AttendanceCycle(
            classroomId = classroomId,
            classroomName = className ?: "",
            teacherId = teacherId,
            teacherName = teacherName,
            sessionId = sessionId
        )

        // 🔹 Restore in memory
        activeSessions[Pair(classroomId, teacherId)] = cycle
        currentVisibleClassroomId = classroomId
        currentTeacherId = teacherId

        Log.d(TAG, "Restoring last cycle for class: $classroomId, teacher=$teacherId, session=$sessionId")

        // 🔹 Inflate ClassroomScanFragment (for resume UI)
        var classroomFragment = supportFragmentManager.findFragmentByTag("CLASSROOM") as? ClassroomScanFragment
        if (classroomFragment == null) {
            classroomFragment = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, classroomFragment, "CLASSROOM")
                .commitNow()
        } else {
            supportFragmentManager.executePendingTransactions()
        }

        // 🔹 Restore UI info
        val fragView = classroomFragment.view
        val layout = fragView?.findViewById<LinearLayout>(R.id.layoutResumeInfo)
        val tvClass = fragView?.findViewById<TextView>(R.id.tvResumeClass)
        val tvTeacher = fragView?.findViewById<TextView>(R.id.tvResumeTeacher)
        val tvStudents = fragView?.findViewById<TextView>(R.id.tvResumeStudents)

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@AttendanceActivity)
                val studentCount = db.attendanceDao().getAttendancesForClass(sessionId, classroomId).size

                runOnUiThread {
                    layout?.visibility = View.VISIBLE
                    tvClass?.text = "Resumed Class: ${className ?: "-"}"
                    tvTeacher?.text = "Teacher: ${teacherName ?: "-"}"
                    tvStudents?.text = "Present Students: $studentCount"
                }

                Toast.makeText(
                    this@AttendanceActivity,
                    "Resumed Class- $teacherName",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error restoring last cycle UI: ${e.message}", e)
            }
        }
    }


    private fun restorePendingSessions() {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@AttendanceActivity)
                val sessions = db.sessionDao().getAllSessions()

                // 🔹 Loop through all sessions that are still open (no endTime)
                sessions.filter { it.endTime.isNullOrEmpty() }.forEach { s ->
                    val classObj = db.classDao().getClassById(s.classId)
                    val classShort = classObj?.classShortName ?: s.classId
                    val teacherId = s.teacherId ?: return@forEach
                    val key = Pair(s.classId, teacherId)

                    val teacherName = db.teachersDao().getTeacherNameById(teacherId) ?: "Unknown"
                    if (!activeSessions.containsKey(key)) {
                        val restoredCycle = AttendanceCycle(
                            classroomId = s.classId,
                            classroomName = classShort,
                            teacherId = teacherId,
                            teacherName = teacherName,
                            sessionId = s.sessionId
                        )
                        activeSessions[key] = restoredCycle

                        // 🔹 Store in ActiveClassCycle table (DB persistence)
                        db.activeClassCycleDao().insert(
                            ActiveClassCycle(
                                classroomId = s.classId,
                                classroomName = classShort,
                                teacherId = teacherId,
                                teacherName = "",
                                sessionId = s.sessionId,
                                startedAtMillis = System.currentTimeMillis()
                            )
                        )

                        Log.d(TAG, "Restored pending session: ${s.sessionId} for class ${s.classId} (teacher $teacherId)")
                    }
                }

                // 🔹 Auto-resume first available class on UI
                if (activeSessions.isNotEmpty()) {
                    val first = activeSessions.values.first()
                    currentVisibleClassroomId = first.classroomId
                    currentTeacherId = first.teacherId

                    val frag = StudentScanFragment.newInstance(first.teacherName ?: "", first.sessionId ?: "")

                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag, "STUDENT")
                        .commitAllowingStateLoss()

                    Toast.makeText(
                        this@AttendanceActivity,
                        "Resumed class: ${first.classroomId} (teacher ${first.teacherId})",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error restoring sessions: ${e.message}", e)
            }
        }
    }

    private fun getEstimatedCurrentTime(): Date {
        val prefs = getSharedPreferences("SyncPrefs", MODE_PRIVATE)
        val lastSyncStr = prefs.getString("last_sync_time", null) ?: return Date()
        val lastUptime = prefs.getLong("last_sync_uptime", 0L)
        if (lastUptime == 0L) return Date()

        return try {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
            val lastSyncDate = sdf.parse(lastSyncStr) ?: return Date()
            val uptimeDiff = SystemClock.elapsedRealtime() - lastUptime
            Date(lastSyncDate.time + uptimeDiff)
        } catch (e: Exception) {
            Date()
        }
    }


    private fun isLastSyncExpired(): Boolean {
        val prefs = getSharedPreferences("SyncPrefs", MODE_PRIVATE)
        val lastUptime = prefs.getLong("last_sync_uptime", 0L)
        if (lastUptime == 0L) return true

        val diffMillis = SystemClock.elapsedRealtime() - lastUptime
        val diffHours = diffMillis / (1000 * 60 * 60)

        return diffHours > 24
    }




    // Fetch active session from DB
    private suspend fun getActiveSession(classId: String, teacherId: String): ActiveClassCycle? {
        val db = AppDatabase.getDatabase(this)
        return db.activeClassCycleDao().getAll()
            .find { it.classroomId == classId && it.teacherId == teacherId }
    }

    // Save active session to DB
    private suspend fun saveActiveSession(cycle: AttendanceCycle) {
        val db = AppDatabase.getDatabase(this)
        db.activeClassCycleDao().insert(
            ActiveClassCycle(
                classroomId = cycle.classroomId,
                classroomName = cycle.classroomName,
                teacherId = cycle.teacherId,
                teacherName = cycle.teacherName,
                sessionId = cycle.sessionId,
                startedAtMillis = cycle.startedAtMillis
            )
        )
    }

    //  Remove active session from DB
    private suspend fun removeActiveSession(classId: String, teacherId: String?) {
        val db = AppDatabase.getDatabase(this)
        val all = db.activeClassCycleDao().getAll()
        all.find { it.classroomId == classId && it.teacherId == teacherId }?.let {
            db.activeClassCycleDao().delete(it)
        }
    }


}
