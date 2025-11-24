package com.example.login.view

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.utility.FaceNetHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

class FaceRecogniseActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvMatchStatus: TextView
    private lateinit var tvUserName: TextView
    private lateinit var tvUserRole: TextView
    private lateinit var tvExtraInfo: TextView
    private lateinit var faceGuide: android.view.View

    private lateinit var faceNet: FaceNetHelper
    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService

    private val DIST_THRESHOLD = 0.60f
    private val CROP_SCALE = 1.1f

    private var lastProcessTime = 0L
    private var faceStableStart = 0L
    private var isVerifying = false

    // LIVENESS VARIABLES (copied from StudentScanFragment)
    private var prevFace: Face? = null
    private var lastLeftProb = -1f
    private var lastRightProb = -1f
    private var blinkDetected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_recognise)

        viewFinder = findViewById(R.id.viewFinder)
        faceGuide = findViewById(R.id.faceGuide)

        tvMatchStatus = findViewById(R.id.tvMatchStatus)
        tvUserName = findViewById(R.id.tvUserName)
        tvUserRole = findViewById(R.id.tvUserRole)
        tvExtraInfo = findViewById(R.id.tvExtraInfo)

        faceNet = FaceNetHelper(this)
        cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // -----------------------------------------------------------
    // CAMERA SETUP
    // -----------------------------------------------------------
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
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
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )

        }, ContextCompat.getMainExecutor(this))
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

    // -----------------------------------------------------------
    // FRAME PROCESSING
    // -----------------------------------------------------------
    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 140) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        try {


            // ------- BRIGHTNESS CHECK (same as Teacher fragment) -------
            val yBuffer = imageProxy.planes[0].buffer.duplicate()
            var sum = 0L
            val total = yBuffer.remaining()

            while (yBuffer.hasRemaining()) {
                sum += (yBuffer.get().toInt() and 0xFF)
            }

            val brightness = if (total > 0) sum / total else 0L

            runOnUiThread {
                if (brightness < 40) {
                    tvExtraInfo.text = "Low Light! Improve lighting."
                } else {
                    tvExtraInfo.text = ""
                }
            }


            val bmp = imageProxyToBitmapUpright(imageProxy)
            val mirrored = mirrorBitmap(bmp)
            val image = InputImage.fromBitmap(mirrored, 0)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        faceGuide.background.setTint(Color.RED)
                        faceStableStart = 0L
                        prevFace = null

                    } else {
                        val face = faces[0]

                        // LIVENESS CHECK (same as StudentScanFragment)
                        if (!isLiveFace(face, prevFace)) {
                            faceGuide.background.setTint(Color.RED)
                            faceStableStart = 0L
                            imageProxy.close()
                            prevFace = face
                            return@addOnSuccessListener
                        }

                        val rect = face.boundingBox

                        val centered = kotlin.math.abs(rect.centerX() - mirrored.width / 2) < rect.width() * 0.3
                                && kotlin.math.abs(rect.centerY() - mirrored.height / 2) < rect.height() * 0.3

                        if (centered) {
                            if (faceStableStart == 0L) faceStableStart = System.currentTimeMillis()

                            val elapsed = System.currentTimeMillis() - faceStableStart
                            faceGuide.background.setTint(if (elapsed >= 300) Color.GREEN else Color.YELLOW)

                            if (elapsed >= 1000 && !isVerifying) {
                                isVerifying = true

                                lifecycleScope.launch(Dispatchers.Default) {
                                    val crop = cropWithScale(mirrored, face.boundingBox, CROP_SCALE)
                                    val emb = faceNet.getFaceEmbedding(crop)

                                    withContext(Dispatchers.Main) {
                                        verifyFace(emb)
                                        faceStableStart = 0L
                                        isVerifying = false
                                    }
                                }
                            }
                        } else {
                            faceGuide.background.setTint(Color.RED)
                            faceStableStart = 0L
                        }

                        prevFace = face
                    }
                }
                .addOnCompleteListener { imageProxy.close() }

        } catch (e: Exception) {
            imageProxy.close()
        }
    }

    // -----------------------------------------------------------
    // LIVENESS (copied from StudentScanFragment)
    // -----------------------------------------------------------
    private fun isLiveFace(face: Face, prevFace: Face?): Boolean {
        val left = face.leftEyeOpenProbability ?: -1f
        val right = face.rightEyeOpenProbability ?: -1f

        // ------------ 1) REAL BLINK DETECTION ------------
        if (left >= 0 && right >= 0) {
            val eyesWereOpen = lastLeftProb > 0.5f && lastRightProb > 0.5f
            val eyesNowClosed = left < 0.4f && right < 0.4f

            // detect blink: open → closed
            if (eyesWereOpen && eyesNowClosed) {
                blinkDetected = true
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

    // -----------------------------------------------------------
    // MATCH LOGIC (real-time DB comparison)
    // -----------------------------------------------------------
    private fun verifyFace(faceEmbedding: FloatArray) {

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@FaceRecogniseActivity)

            var bestDist = Float.MAX_VALUE
            var bestName = "Unknown"
            var bestId: String? = null
            var bestRole = "Unknown"

            // --- CHECK TEACHERS IN DB ---
            val teachers = db.teachersDao().getAllTeachers()
            for (t in teachers) {
                val embStr = t.embedding ?: continue
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.isEmpty()) continue

                val dist = faceNet.calculateDistance(emb, faceEmbedding)
                if (dist < bestDist) {
                    bestDist = dist
                    bestName = t.staffName
                    bestId = t.staffId
                    bestRole = "Teacher"
                }
            }

            // --- CHECK STUDENTS IN DB ---
            val students = db.studentsDao().getAllStudents()
            for (s in students) {
                val embStr = s.embedding ?: continue
                val emb = embStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (emb.isEmpty()) continue

                val dist = faceNet.calculateDistance(emb, faceEmbedding)
                if (dist < bestDist) {
                    bestDist = dist
                    bestName = s.studentName
                    bestId = s.studentId
                    bestRole = "Student"
                }
            }

            // --- EVALUATE MATCH ---
            withContext(Dispatchers.Main) {
                if (bestDist >= DIST_THRESHOLD || bestId == null) {
                    showResult("Face Not Recognized", "", "", "")
                    return@withContext
                }

                // FETCH EXTRA INFO
                lifecycleScope.launch(Dispatchers.IO) {
                    var extraInfo = ""
                    val db2 = AppDatabase.getDatabase(this@FaceRecogniseActivity)

                    if (bestRole == "Student") {
                        val st = db2.studentsDao().getStudentById(bestId)
                        extraInfo = "Class: ${st?.classId ?: "--"}"
                    } else {
                        val tc = db2.teachersDao().getTeacherById(bestId)
                        // extraInfo = "Department: ${tc?.dept ?: "--"}"
                    }

                    withContext(Dispatchers.Main) {
                        showResult("Face Matched", bestName, bestRole, extraInfo)
                    }
                }
            }
        }
    }

    private fun showResult(status: String, name: String, role: String, extra: String) {
        tvMatchStatus.text = status
        tvUserName.text = name
        tvUserRole.text = role
        tvExtraInfo.text = extra

        // 🔥 Reset UI after 5 seconds
        tvMatchStatus.postDelayed({
            tvMatchStatus.text = "Face Not Detected"
            tvUserName.text = ""
            tvUserRole.text = ""
            tvExtraInfo.text = ""

            // Optional: reset liveness to allow new scan
            blinkDetected = false
            lastLeftProb = -1f
            lastRightProb = -1f
            prevFace = null
            isVerifying = false

            faceGuide.background.setTint(Color.RED)

        }, 5000)   // 5000 ms = 5 seconds
    }

    // -----------------------------------------------------------
    // UTILS
    // -----------------------------------------------------------
    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuvToNv21(imageProxy)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuvToNv21(image: ImageProxy): ByteArray {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer

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


    private fun imageProxyToBitmapUpright(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuvToNv21(imageProxy)
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


}
