package com.example.login.view

import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import com.example.login.R


class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView

    private var faceStableStart = 0L
    private var captured = false
    private var lastProcessTime = 0L

    // Front camera is mirrored in UX; we mirror BEFORE detection & embedding for consistency
    private val MIRROR_FRONT = true
    private val CROP_SCALE = 1.3f

    // NEW views
  //  private lateinit var previewView: PreviewView
    private lateinit var overlayPanel: View
    private lateinit var dimView: View
    private lateinit var capturedImage: ImageView
    private lateinit var btnTryAgain: View
    private lateinit var btnSubmit: View

    // Hold last captured bitmap until user decides
    private var lastCaptured: Bitmap? = null


    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        // bind views
        previewView = findViewById(R.id.previewView)
        overlayPanel = findViewById(R.id.overlayPanel)
        dimView = findViewById(R.id.dimView)
        capturedImage = findViewById(R.id.capturedImage)
        btnTryAgain = findViewById(R.id.btnTryAgain)
        btnSubmit = findViewById(R.id.btnSubmit)

        btnTryAgain.setOnClickListener {
            // hide overlay and resume analysis
            overlayPanel.visibility = View.GONE
            dimView.visibility = View.GONE
            lastCaptured = null
            captured = false
            faceStableStart = 0L
            // nothing else needed; analyzer is still running
        }

        btnSubmit.setOnClickListener {
            val bmp = lastCaptured ?: return@setOnClickListener
            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            val bytes = stream.toByteArray()

            val reply = intent
            reply.putExtra("face_bytes", bytes)
            setResult(RESULT_OK, reply)
            finish()
        }

        startCamera()
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { ia ->
                    ia.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                        analyzeAndCapture(imageProxy)
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeAndCapture(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 300) { // ~3 FPS analysis
            imageProxy.close()
            return
        }
        lastProcessTime = now

        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }

        // Build a correctly oriented & mirrored bitmap ONCE
        val rotated = imageProxyToBitmapUpright(imageProxy)
        val prepared = if (MIRROR_FRONT) mirrorBitmap(rotated) else rotated

        val input = InputImage.fromBitmap(prepared, 0)

        detector.process(input)
            .addOnSuccessListener { faces ->
                if (captured) return@addOnSuccessListener
                if (faces.isEmpty()) {
                    faceStableStart = 0L
                    return@addOnSuccessListener
                }

                // Require face stability for ~1.2s
                val t = System.currentTimeMillis()
                if (faceStableStart == 0L) faceStableStart = t
                val stable = t - faceStableStart > 3000

                if (!stable) return@addOnSuccessListener

                val faceBox = faces[0].boundingBox
                val cropped = cropWithScale(prepared, faceBox, CROP_SCALE)

                // === NEW: show preview & wait for user action ===
                lastCaptured = cropped
                captured = true                      // pause further auto captures
                capturedImage.setImageBitmap(cropped)
                dimView.visibility = View.VISIBLE
                overlayPanel.visibility = View.VISIBLE
            }
            .addOnFailureListener {
                Toast.makeText(this, "Face detect error: ${it.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    // --- Helpers ---

    private fun imageProxyToBitmapUpright(imageProxy: ImageProxy): Bitmap {
        val nv21 = yuv420ToNv21(imageProxy)
        val yuv = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Rotate to upright based on sensor rotation
        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
        if (rotation == 0f) return raw
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun mirrorBitmap(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
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
