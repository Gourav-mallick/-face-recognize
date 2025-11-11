package com.example.login.view

import android.Manifest
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

    // Args
    private var teacherNameArg = ""
    private var sessionIdArg = ""

    // Camera Vars
    private lateinit var cameraExecutor: ExecutorService
    private var isVerifying = false
    private var faceStableStart = 0L
    private var lastProcessTime = 0L

    private lateinit var faceNet: FaceNetHelper

    private val DIST_THRESHOLD = 0.80f
    private val CROP_SCALE = 1.3f
    private val MIRROR_FRONT = true

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

        if (allPermissionsGranted()) startCamera()
        else requestPermissions(REQUIRED_PERMISSIONS, 101)

        updatePresentCountUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }

    // -----------------------------------------------------------------------
    // CAMERA LOGIC (same as TeacherScanFragment)
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

    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 300) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        try {
            val bmp = imageProxyToBitmapUpright(imageProxy)
            val displayBmp = if (MIRROR_FRONT) mirrorBitmap(bmp) else bmp

            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(displayBmp, 0)

            val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
                com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                    .setPerformanceMode(
                        com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                    ).build()
            )

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        faceGuide.background.setTint(Color.RED)
                        faceStableStart = 0L
                    } else {
                        val face = faces[0]
                        val rect = face.boundingBox

                        val isCentered = kotlin.math.abs(rect.centerX() - displayBmp.width / 2) < rect.width() * 0.3 &&
                                kotlin.math.abs(rect.centerY() - displayBmp.height / 2) < rect.height() * 0.3

                        if (isCentered) {
                            if (faceStableStart == 0L) faceStableStart = System.currentTimeMillis()
                            val elapsed = System.currentTimeMillis() - faceStableStart
                            faceGuide.background.setTint(if (elapsed >= 800) Color.GREEN else Color.YELLOW)

                            if (elapsed >= 800 && !isVerifying) {
                                isVerifying = true

                                val cropped = cropWithScale(displayBmp, face.boundingBox, CROP_SCALE)
                                val embedding = faceNet.getFaceEmbedding(cropped)

                                verifyFace(embedding)
                                faceStableStart = 0L
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
    // FACE MATCHING LOGIC (Option A: use teachers.embedding + students.embedding)
    // -----------------------------------------------------------------------
    private fun verifyFace(faceEmbedding: FloatArray) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())

            val session = db.sessionDao().getSessionById(sessionIdArg)
            if (session == null) {
                done(); toast("Invalid session")
                return@launch
            }

            val currentTeacherId = session.teacherId
            val classId = session.classId

            // Load stored embeddings
            val teachers = db.teachersDao().getAllTeachers()
            val students = db.studentsDao().getAllStudents()

            var bestMatchName = "Unknown"
            var bestMatchId: String? = null
            var bestIsTeacher = false
            var minDist = Float.MAX_VALUE

            // Check teacher embeddings
            for (t in teachers) {
                val embStr = t.embedding ?: continue
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.isEmpty()) continue

                val dist = faceNet.calculateDistance(emb, faceEmbedding)
                if (dist < minDist) {
                    minDist = dist
                    bestMatchName = t.staffName
                    bestMatchId = t.staffId
                    bestIsTeacher = true
                }
            }

            // Check students
            for (s in students) {
                val embStr = s.embedding ?: continue
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.isEmpty()) continue

                val dist = faceNet.calculateDistance(emb, faceEmbedding)
                if (dist < minDist) {
                    minDist = dist
                    bestMatchName = s.studentName
                    bestMatchId = s.studentId
                    bestIsTeacher = false
                }
            }

            // Evaluate result
            withContext(Dispatchers.Main) {
                if (bestMatchId == null || minDist >= DIST_THRESHOLD) {
                    toast("Unknown face")
                    done()
                    return@withContext
                }

                // 1) Teacher match
                if (bestIsTeacher) {
                    if (bestMatchId == currentTeacherId) {
                        // Same teacher: show exit popup using your existing AttendanceActivity logic
                        (requireActivity() as AttendanceActivity).showEndClassDialogForVisibleClass()
                    } else {
                        // Ignore different teacher
                        toast("Different teacher — ignored")
                    }

                    done()
                    return@withContext
                }

                // 2) Student match
                val matchedStudent = db.studentsDao().getStudentById(bestMatchId!!)
                if (matchedStudent == null) {
                    toast("Student not found in DB")
                    done()
                    return@withContext
                }

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
            val count = db.attendanceDao().getAttendancesForSession(sessionIdArg).size
            withContext(Dispatchers.Main) { tvPresentCount.text = count.toString() }
        }
    }



    // -----------------------------------------------------------------------
    // SMALL UTILITIES
    // -----------------------------------------------------------------------
    private fun done() { isVerifying = false }

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
}
