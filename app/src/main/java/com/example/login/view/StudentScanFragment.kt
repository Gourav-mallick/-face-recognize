package com.example.login.view

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.camera.view.PreviewView
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Student
import com.example.login.utility.FaceNetHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class StudentScanFragment : Fragment() {

    // Camera UI components
    private lateinit var viewFinder: PreviewView
    private lateinit var faceGuide: View
    private lateinit var tvLightWarning: TextView

    // Info UI components
    private lateinit var tvTeacherName: TextView
    private lateinit var tvPresentCount: TextView
    private lateinit var tvLastStudent: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var tvLatestCardTapStudentLabel: TextView
    private var prevFace: com.google.mlkit.vision.face.Face? = null
    // Blink detection variables
    private var lastLeftProb = -1f
    private var lastRightProb = -1f
    private var blinkDetected = false


    // Args
    private var teacherNameArg = ""
    private var sessionIdArg = ""

    // Camera Vars
    private lateinit var cameraExecutor: ExecutorService
    private var isVerifying = false
    private var faceStableStart = 0L
    private var lastProcessTime = 0L

    private lateinit var faceNet: FaceNetHelper

    private val DIST_THRESHOLD = 0.60f
    private val CROP_SCALE = 1.1f
    private val MIRROR_FRONT = true

    private var studentFailCount = 0
    private val MAX_STUDENT_FAILS = 3   // or 4 if you want


    //Add a preloaded cache
    private val cachedStudentEmbeddings = mutableListOf<Triple<String, String, FloatArray>>()
    private val cachedTeacherEmbeddings = mutableListOf<Triple<String, String, FloatArray>>()
    private var cacheLoaded = false


    companion object {
        private const val ARG_TEACHER = "arg_teacher"
        private const val ARG_SESSION_ID = "arg_session_id"

        fun newInstance(teacherName: String, sessionId: String) =
            StudentScanFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TEACHER, teacherName)
                    putString(ARG_SESSION_ID, sessionId)
                }
            }

        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_student_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        // Bind UI
        viewFinder = view.findViewById(R.id.viewFinder)
        faceGuide = view.findViewById(R.id.faceGuide)
        tvLightWarning = view.findViewById(R.id.tvLightWarning)

        tvTeacherName = view.findViewById(R.id.tvTeacherName)
        tvPresentCount = view.findViewById(R.id.tvPresentCount)
        tvLastStudent = view.findViewById(R.id.tvLastStudent)
        tvInstruction = view.findViewById(R.id.tvInstruction)
        tvLatestCardTapStudentLabel = view.findViewById(R.id.tvLatestCardTapStudentLabel)

        teacherNameArg = arguments?.getString(ARG_TEACHER, "") ?: ""
        sessionIdArg = arguments?.getString(ARG_SESSION_ID, "") ?: ""

        tvTeacherName.text = teacherNameArg
        tvInstruction.text = "Scan Student Face"

        faceNet = FaceNetHelper(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        // ---------- LOAD CACHE ONCE ----------
        loadFaceEmbeddingCache()


        if (allPermissionsGranted()) startCamera()
        else requestPermissions(REQUIRED_PERMISSIONS, 101)

        updatePresentCountUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }

    // -----------------------------------------------------------------------
    // CAMERA LOGIC
    // -----------------------------------------------------------------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
    }



    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 130) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        try {
            val bmp = imageProxyToBitmapUpright(imageProxy)
            val displayBmp = if (MIRROR_FRONT) mirrorBitmap(bmp) else bmp

            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(displayBmp, 0)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        faceGuide.background.setTint(Color.RED)
                        faceStableStart = 0L
                    } else {
                        val face = faces[0]
                        // 🔹 LIVENESS CHECK (eye open probability)
                        if (!isLiveFace(face, prevFace)) {
                            faceGuide.background.setTint(Color.RED)
                            faceStableStart = 0L
                            prevFace = face
                            return@addOnSuccessListener
                        }


                        val rect = face.boundingBox

                        val isCentered = kotlin.math.abs(rect.centerX() - displayBmp.width / 2) < rect.width() * 0.3 &&
                                kotlin.math.abs(rect.centerY() - displayBmp.height / 2) < rect.height() * 0.3

                        if (isCentered) {
                            if (faceStableStart == 0L) faceStableStart = System.currentTimeMillis()
                            val elapsed = System.currentTimeMillis() - faceStableStart
                            faceGuide.background.setTint(if (elapsed >= 300) Color.GREEN else Color.YELLOW)

                            if (elapsed >= 1000 && !isVerifying) {
                                isVerifying = true

                                lifecycleScope.launch(Dispatchers.Default) {

                                    val cropped = cropWithScale(displayBmp, face.boundingBox, CROP_SCALE)
                                    val embedding = faceNet.getFaceEmbedding(cropped)

                                    withContext(Dispatchers.Main) {
                                        verifyFace(embedding)
                                        faceStableStart = 0L
                                        isVerifying = false
                                    }
                                }
                            }
                        } else {
                            faceGuide.background.setTint(Color.RED)
                            faceStableStart = 0L
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }

        } catch (e: Exception) {
            imageProxy.close()
        }
    }

    // -----------------------------------------------------------------------
    // FACE MATCHING LOGIC ( use teachers.embedding + students.embedding)
    // -----------------------------------------------------------------------
    private fun verifyFace(faceEmbedding: FloatArray) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())

            val session = db.sessionDao().getSessionById(sessionIdArg)
            if (session == null) {
                done()
                toast("Unable to continue. Please restart the attendance process.")
                return@launch
            }

            // 1. Get teacher from session
            val teacherId = session.teacherId


            var bestMatchName = "Unknown"
            var bestMatchId: String? = null
            var bestIsTeacher = false
            var minDist = Float.MAX_VALUE

            // Compare faceEmbedding with teachers
            for ((id, name, emb) in cachedTeacherEmbeddings) {
                val dist = faceNet.calculateDistance(emb, faceEmbedding)
                if (dist < minDist) {
                    minDist = dist
                    bestMatchName = name
                    bestMatchId = id
                    bestIsTeacher = true
                }
            }


            // Compare faceEmbedding with students
            for ((id, name, emb) in cachedStudentEmbeddings) {
                val dist = faceNet.calculateDistance(emb, faceEmbedding)
                if (dist < minDist) {
                    minDist = dist
                    bestMatchName = name
                    bestMatchId = id
                    bestIsTeacher = false
                }
            }


            // Evaluate result
            withContext(Dispatchers.Main) {

                //If no match OR match is too far

                if (!bestIsTeacher && (bestMatchId == null || minDist >= DIST_THRESHOLD)) {

                    studentFailCount++

                    if (studentFailCount >= MAX_STUDENT_FAILS) {

                        Toast.makeText(
                            requireContext(),
                            "You are not enrolled for this class.\nPlease contact the administration to complete your enrollment.",
                            Toast.LENGTH_LONG
                        ).show()

                        // Stop verification temporarily to prevent spam scanning
                        isVerifying = true

                        // Reset after 3 seconds so next student can try
                        view?.postDelayed({
                            isVerifying = false
                            studentFailCount = 0
                        }, 1000)

                        done()
                        return@withContext
                    }

                    toast("Face not matched. Adjust your face and try again.")
                    done()
                    return@withContext
                }


                // 1) If the best match is a teacher
                if (bestIsTeacher) {
                    if (bestMatchId == teacherId) {
                        //  Check if there are any students marked present in this session
                        lifecycleScope.launch(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(requireContext())
                            val attendanceCount = db.attendanceDao().getAttendancesForSession(sessionIdArg).size

                            if (attendanceCount == 0) {
                                withContext(Dispatchers.Main) {
                                    AlertDialog.Builder(requireContext())
                                        .setTitle("Close Class?")
                                        .setMessage("No students are present in this class.\nDo you want to close?")
                                        .setPositiveButton("Yes") { dialog, _ ->
                                            dialog.dismiss()
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val currentTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                                    .format(java.util.Date())

                                                // 🔹 End and delete session completely
                                                db.sessionDao().getSessionById(sessionIdArg)?.let { session ->
                                                    db.sessionDao().updateSessionEnd(session.sessionId, currentTime)
                                                    db.attendanceDao().deleteAttendanceForSession(session.sessionId)
                                                    db.activeClassCycleDao().getAll()
                                                        .find { it.sessionId == session.sessionId }
                                                        ?.let { db.activeClassCycleDao().delete(it) }
                                                    db.sessionDao().deleteSessionById(session.sessionId)
                                                }

                                                withContext(Dispatchers.Main) {
                                                    // 🔹 Clear saved app state
                                                    val prefs1 = requireContext().getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
                                                    prefs1.edit().clear().apply()

                                                    val prefs2 = requireContext().getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
                                                    prefs2.edit().clear().apply()

                                                    Toast.makeText(
                                                        requireContext(),
                                                        "Class has been closed. Returning to the main screen.",
                                                        Toast.LENGTH_LONG
                                                    ).show()

                                                    // 🔹 Restart app to starting fragment
                                                    val intent = android.content.Intent(requireContext(), AttendanceActivity::class.java)
                                                    intent.flags =
                                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                    startActivity(intent)
                                                    requireActivity().finish()
                                                }
                                            }
                                        }
                                        .setNegativeButton("Cancel") { dialog, _ ->
                                            dialog.dismiss()
                                            Toast.makeText(requireContext(), "Class not closed", Toast.LENGTH_SHORT).show()
                                        }
                                        .setCancelable(false)
                                        .show()
                                }
                            }

                            else {
                                // 🔹 Students are present — normal flow (show end class popup)
                                withContext(Dispatchers.Main) {
                                    (requireActivity() as AttendanceActivity).showEndClassDialogForVisibleClass()
                                }
                            }
                        }
                    } else {
                        toast("This face belongs to a different teacher.")
                    }

                    done()
                    return@withContext
                }


                // 2) If best match is a student
                val matchedStudent = db.studentsDao().getStudentById(bestMatchId!!)
                if (matchedStudent == null) {
                    toast("Unable to identify the student. Please try again.")
                    done()
                    return@withContext
                }

                Log.d("STUDENT_CLASS_CHECK", "Student ${matchedStudent.studentName} class = ${matchedStudent.classId}")


                // Mark attendance through AttendanceActivity logic (preserve everything)
                (requireActivity() as AttendanceActivity).simulateStudentScan(matchedStudent)

                addStudentUI(matchedStudent)
                done()
            }
        }
    }

    // -----------------------------------------------------------------------
    // UI UPDATE HELPERS
    // -----------------------------------------------------------------------
     fun addStudentUI(student: Student) {
        tvLastStudent.text = student.studentName
        tvLatestCardTapStudentLabel.text = "Latest Student"
        updatePresentCountUI()
    }

    private fun updatePresentCountUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            // Total count of students marked
            val count = db.attendanceDao().getAttendancesForSession(sessionIdArg).size

            // Last student marked (from new DAO query)
            val lastStudent = db.attendanceDao().getLastMarkedStudentNameForSession(sessionIdArg)

            withContext(Dispatchers.Main) {
                tvPresentCount.text = count.toString()
                tvLastStudent.text = lastStudent ?: ""
                tvLatestCardTapStudentLabel.text = if (lastStudent != null) "Latest Student" else ""
            }
        }
    }



    // -----------------------------------------------------------------------
    //  UTILITIES for Reset eye probabilities when face changes
    // -----------------------------------------------------------------------
    private fun done() {
        isVerifying = false
        blinkDetected = false
        lastLeftProb = -1f        //  reset
        lastRightProb = -1f       //  reset
        prevFace = null           //  reset motion reference
    }


    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

    // -----------------------------------------------------------------------
    // IMAGE HELPERS (same as TeacherScanFragment)
    // -----------------------------------------------------------------------
    private fun imageProxyToBitmapUpright(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuv420ToNv21(imageProxy)
        val yuv = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
        if (rotation == 0f) return raw

        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val y = imageProxy.planes[0].buffer
        val u = imageProxy.planes[1].buffer
        val v = imageProxy.planes[2].buffer

        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        v.get(nv21, ySize, vSize)
        u.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    private fun mirrorBitmap(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun cropWithScale(bmp: Bitmap, rect: Rect, scale: Float): Bitmap {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfW = (rect.width() * scale / 2).toInt()
        val halfH = (rect.height() * scale / 2).toInt()

        val x = max(0, cx - halfW)
        val y = max(0, cy - halfH)
        val w = min(bmp.width - x, halfW * 2)
        val h = min(bmp.height - y, halfH * 2)

        return Bitmap.createBitmap(bmp, x, y, w, h)
    }


    private fun isLiveFace(
        face: com.google.mlkit.vision.face.Face,
        prevFace: com.google.mlkit.vision.face.Face?
    ): Boolean {

        val left = face.leftEyeOpenProbability ?: -1f
        val right = face.rightEyeOpenProbability ?: -1f

        Log.d("BLINK_DEBUG_STUDENT", "Left=$left Right=$right")

        // ------------ 1) REAL BLINK DETECTION ------------
        if (left >= 0 && right >= 0) {

            val eyesWereOpen = lastLeftProb > 0.6f && lastRightProb > 0.6f
            val eyesNowClosed = left < 0.3f && right < 0.3f

            // detect blink: open → closed
            if (eyesWereOpen && eyesNowClosed) {
                blinkDetected = true
                Log.d("BLINK_DEBUG_STUDENT", "Blink detected on student!")
            }

            lastLeftProb = left
            lastRightProb = right
        }

        // require 1 blink before considering liveness
        if (!blinkDetected) {
            return false
        }

        // ------------ 2) MOTION LIVENESS ------------
        if (prevFace != null) {
            val moveX = kotlin.math.abs(face.boundingBox.centerX() - prevFace.boundingBox.centerX())
            val moveY = kotlin.math.abs(face.boundingBox.centerY() - prevFace.boundingBox.centerY())

            if (moveX < 1 && moveY < 1) {
                return false // still → printed photo
            }
        }

        return true
    }


    private fun loadFaceEmbeddingCache() {
        if (cacheLoaded) return      // prevents double load
        cacheLoaded = true

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())

            // Load teachers
            db.teachersDao().getAllTeachers().forEach { t ->
                val embStr = t.embedding ?: return@forEach
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.isNotEmpty()) {
                    cachedTeacherEmbeddings.add(Triple(t.staffId, t.staffName, emb))
                }
            }

            // Load students of allowed classes ONLY
            val session = db.sessionDao().getSessionById(sessionIdArg) ?: return@launch
            val allowedClassIds = db.teacherClassMapDao().getClassesForTeacher(session.teacherId)

            db.studentsDao().getStudentsByClasses(allowedClassIds).forEach { s ->
                val embStr = s.embedding ?: return@forEach
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.isNotEmpty()) {
                    cachedStudentEmbeddings.add(Triple(s.studentId, s.studentName, emb))
                }
            }

            Log.d("EMB_CACHE", "Loaded ${cachedTeacherEmbeddings.size} teachers + ${cachedStudentEmbeddings.size} students into cache")
        }
    }


}
