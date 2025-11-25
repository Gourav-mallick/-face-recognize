package com.example.login.view

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.utility.FaceNetHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min


class TeacherScanFragment : Fragment() {

    private lateinit var viewFinder: androidx.camera.view.PreviewView
    private lateinit var faceGuide: View
    private lateinit var tvLightWarning: TextView
    private lateinit var tvClassCard: TextView
    private lateinit var tvStart: TextView
    private lateinit var progress: ProgressBar

    private lateinit var faceNet: FaceNetHelper
    private var cameraExecutor: ExecutorService? = null

    private var faceStableStart = 0L
    private var isVerifying = false
    private var lastProcessTime = 0L
    private var prevFace: com.google.mlkit.vision.face.Face? = null
    private var lastLeftProb = -1f
    private var lastRightProb = -1f
    private var blinkDetected = false



    private val DIST_THRESHOLD = 0.60f     // keep same as activity
    private val CROP_SCALE = 1.1f
    private val MIRROR_FRONT = true

    private var sessionCreated = false


    private var failCount = 0          // count failed attempts
    private val MAX_FAILS = 3          // set how many tries allowed


    companion object {
        private const val ARG_CLASSID  = "arg_classid"
        fun newInstance(classId: String) = TeacherScanFragment().apply {
            arguments = Bundle().apply { putString(ARG_CLASSID , classId) }
        }
    }

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // 🔥 Required!
                .build()
        )
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?)
            = inflater.inflate(R.layout.fragment_teacher_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewFinder = view.findViewById(R.id.viewFinder)
        faceGuide = view.findViewById(R.id.faceGuide)
        tvLightWarning = view.findViewById(R.id.tvLightWarning)
        tvClassCard = view.findViewById(R.id.tvClassCard)
        tvStart = view.findViewById(R.id.tvStart)
        progress = ProgressBar(requireContext()).apply { visibility = View.GONE }
        (view as ViewGroup).addView(progress)

        tvClassCard.text = "Class-Room : ${arguments?.getString(ARG_CLASSID) ?: "-"}"

        faceNet = FaceNetHelper(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1001)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { ia ->
                    ia.setAnalyzer(cameraExecutor!!) { imageProxy -> processFrame(imageProxy) }
                }

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
        if (now - lastProcessTime < 130) {
            imageProxy.close(); return
        }
        lastProcessTime = now

        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val rotated = imageProxyToBitmapUpright(imageProxy)
        val prepared = if (MIRROR_FRONT) mirrorBitmap(rotated) else rotated

        // Light warning using Y plane mean
        val yBuffer: ByteBuffer = imageProxy.planes[0].buffer.duplicate()
        var sum = 0L; val count = yBuffer.remaining()
        while (yBuffer.hasRemaining()) sum += (yBuffer.get().toInt() and 0xFF)
        val brightness = if (count > 0) sum / count else 0L
        requireActivity().runOnUiThread {
            tvLightWarning.visibility = if (brightness < 40) View.VISIBLE else View.GONE
        }

        val image = InputImage.fromBitmap(prepared, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {

                    val face = faces[0]   // ← moved here before liveness check

                    // 🔹 LIVENESS CHECK (Eye open probability)
                    if (!isLiveFace(face,prevFace)) {
                        faceGuide.background.setTint(Color.RED)
                        faceStableStart = 0L
                        prevFace = face
                        return@addOnSuccessListener
                    }

                    faceGuide.background.setTint(Color.GREEN)
                    if (faceStableStart == 0L) faceStableStart = now

                    if (now - faceStableStart > 1000 && !isVerifying) {
                        isVerifying = true

                        requireActivity().runOnUiThread { progress.visibility = View.VISIBLE }

                       // val face = faces[0]
                        val cropped = cropWithScale(prepared, face.boundingBox, CROP_SCALE)
                        val embedding = faceNet.getFaceEmbedding(cropped)

                        recognizeTeacher(embedding)
                        faceStableStart = 0L
                    }
                } else {
                    faceGuide.background.setTint(Color.RED)
                    faceStableStart = 0L
                }
            }
            .addOnFailureListener { Log.e("TeacherScan", "Face detect error: ${it.message}") }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun recognizeTeacher(embedding: FloatArray) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val teachers = db.teachersDao().getAllTeachers() // has embedding String? field
            if (teachers.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "No teachers Found!", Toast.LENGTH_SHORT).show()
                    progress.visibility = View.GONE; isVerifying = false
                }
                return@launch
            }

            var bestId: String? = null
            var bestName: String? = null
            var minDist = Float.MAX_VALUE

            for (t in teachers) {
                val embStr = t.embedding ?: continue
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.isEmpty()) continue
                val dist = faceNet.calculateDistance(emb, embedding)
                if (dist < minDist) {
                    minDist = dist
                    bestId = t.staffId
                    bestName = t.staffName
                }
            }

            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                isVerifying = false

                if (bestId != null && minDist < DIST_THRESHOLD) {
                    isVerifying = true

                    // 🔥 1) Check assigned classes
                    val hasClasses = withContext(Dispatchers.IO) { hasAssignedClasses(bestId!!) }


                    if (!hasClasses) {
                        //  Teacher NOT assigned to any class
                        val message = "$bestName,\n you are not enrolled in any class.\nPlease contact authority."

                        AlertDialog.Builder(requireContext())
                            .setTitle("Access Denied")
                            .setMessage(message)
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ ->


                                // 🔥 Navigate back to Classroom Scan starting screen
                               // parentFragmentManager.popBackStack()   // go back 1 step

                                // If you want to CLEAR ALL and go to root of scanning:
                                parentFragmentManager.popBackStack("TEACHER_SCAN",  FragmentManager.POP_BACK_STACK_INCLUSIVE)
                                isVerifying = false
                            }
                            .show()

                        return@withContext
                    }

                    // 🔥 CALL NEW FUNCTION HERE
                  //  logTeacherAssignedClasses(bestId!!)

                    sessionCreated = true
                    //  Valid teacher recognized
                    Toast.makeText(requireContext(), "Welcome, $bestName", Toast.LENGTH_LONG).show()

                    //  Wait 5 seconds, then navigate to StudentScanFragment
                    view?.postDelayed({
                        (requireActivity() as AttendanceActivity).simulateTeacherScan(bestId!!)
                    }, 2000)
                } else {

                    failCount++

                    if (failCount >= MAX_FAILS) {

                        Toast.makeText(
                            requireContext(),
                            "Face not recognized.\nYou are not enrolled any class.\nPlease contact authority to Enroll.",
                            Toast.LENGTH_LONG
                        ).show()

                        // OPTIONAL: stop scanning for 3 seconds
                        isVerifying = true
                        view?.postDelayed({
                            isVerifying = false
                            failCount = 0   // reset so next teacher can try
                        }, 3000)

                        return@withContext
                    }

                    // Normal fail toast
                    Toast.makeText(
                        requireContext(),
                        "Face not matched. Adjust your face and try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            }
        }
    }


    private suspend fun hasAssignedClasses(teacherId: String): Boolean {
        return try {
            val db = AppDatabase.getDatabase(requireContext())
            val classIds = db.teacherClassMapDao().getClassesForTeacher(teacherId)
            classIds.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }


    // === helpers (same as activity) ===
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
        val cx = rect.centerX(); val cy = rect.centerY()
        val halfW = (rect.width() * scale / 2).toInt()
        val halfH = (rect.height() * scale / 2).toInt()
        val x = max(0, cx - halfW)
        val y = max(0, cy - halfH)
        val w = min(bmp.width - x, halfW * 2)
        val h = min(bmp.height - y, halfH * 2)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor?.shutdown()
        if (!sessionCreated) {
            // Clear saved screen only if no session was started
            val prefs = requireContext().getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
            prefs.edit().remove("CURRENT_SCREEN").apply()
        }
    }

    override fun onResume() {
        super.onResume()

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sessionCreated) {
                    Toast.makeText(
                        requireContext(),
                        "Cannot go back after session started",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // ✅ Clear saved screen if no session started
                    val prefs = requireContext().getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
                    prefs.edit().remove("CURRENT_SCREEN").apply()

                    // ✅ Navigate back to classroom
                    parentFragmentManager.popBackStack()
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun isLiveFace(face: com.google.mlkit.vision.face.Face, prevFace: com.google.mlkit.vision.face.Face?): Boolean {

        val left = face.leftEyeOpenProbability ?: -1f
        val right = face.rightEyeOpenProbability ?: -1f
       // Toast.makeText(requireContext(), "Blink your Eyes", Toast.LENGTH_SHORT).show()

        Log.d("BLINK_DEBUG", "Left=$left Right=$right")

        // ----- 1) REAL BLINK DETECTION -----
        if (left >= 0 && right >= 0) {

            val eyesWereOpen = lastLeftProb > 0.6f && lastRightProb > 0.6f
            val eyesNowClosed = left < 0.3f && right < 0.3f

            // BLINK event: open → closed
            if (eyesWereOpen && eyesNowClosed) {
                blinkDetected = true
                Log.d("BLINK_DEBUG", "Blink DETECTED!")
            }

            lastLeftProb = left
            lastRightProb = right
        }

        // REQUIRE blink for liveness
        if (!blinkDetected) {
            return false
        }

        // ----- 2) MOTION LIVENESS -----
        if (prevFace != null) {
            val moveX = kotlin.math.abs(face.boundingBox.centerX() - prevFace.boundingBox.centerX())
            val moveY = kotlin.math.abs(face.boundingBox.centerY() - prevFace.boundingBox.centerY())

            if (moveX < 1 && moveY < 1) {
                return false
            }
        }

        return true
    }


    private fun logTeacherAssignedClasses(teacherId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(requireContext())

                // 1️⃣ Get classes mapped to this teacher
                val classIds = db.teacherClassMapDao().getClassesForTeacher(teacherId)

                if (classIds.isEmpty()) {
                    Log.d("TEACHER_CLASSES", "Teacher $teacherId has NO assigned classes.")
                    return@launch
                }

                // 2️⃣ Get class names
                val classNames = classIds.mapNotNull { id ->
                    db.classDao().getClassById(id)?.classShortName
                }

                // 3️⃣ Print in LOG
                Log.d(
                    "TEACHER_CLASSES",
                    "Teacher $teacherId Assigned Classes → IDs=$classIds NAMES=$classNames"
                )

            } catch (e: Exception) {
                Log.e("TEACHER_CLASSES", "Error fetching assigned classes: ${e.message}")
            }
        }
    }


}
