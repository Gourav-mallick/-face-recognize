package com.example.login.view

import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
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
    private val CROP_SCALE = 1.1f

    // NEW views
    //  private lateinit var previewView: PreviewView
    private lateinit var overlayPanel: View
    private lateinit var dimView: View
    private lateinit var capturedImage: ImageView
    private lateinit var btnTryAgain: View
    private lateinit var btnSubmit: View

    // Hold last captured bitmap until user decides
    private var lastCaptured: Bitmap? = null


    private var captureStep = 0  // 0=LEFT, 1=RIGHT, 2=CENTER
    private val capturedBitmaps = arrayListOf<Bitmap>()
    private lateinit var tvStep: TextView

    private lateinit var ivLeftArrow: ImageView
    private lateinit var ivCenterArrow: ImageView
    private lateinit var ivRightArrow: ImageView

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
        tvStep = findViewById(R.id.tvStep)

        ivLeftArrow = findViewById(R.id.ivLeftArrow)
        ivCenterArrow = findViewById(R.id.ivCenterArrow)
        ivRightArrow = findViewById(R.id.ivRightArrow)

        val tvUserInfo = findViewById<TextView>(R.id.tvUserInfo)

        val name = intent.getStringExtra("user_name") ?: ""
        val id = intent.getStringExtra("user_id") ?: ""

        tvUserInfo.text = "$name ($id)"


        btnSubmit.setOnClickListener {

            // Save captured image for this step
            lastCaptured?.let { capturedBitmaps.add(it) }

            overlayPanel.visibility = View.GONE
            dimView.visibility = View.GONE

            // Move to next step
            captureStep++

            if (captureStep < 3) {
                updateStepText()
                captured = false
                faceStableStart = 0L
            } else {
                tvStep.text = "All photos captured"

                // Now return images to caller
                val img1 = bitmapToBytes(capturedBitmaps[0])
                val img2 = bitmapToBytes(capturedBitmaps[1])
                val img3 = bitmapToBytes(capturedBitmaps[2])

                val reply = intent
                reply.putExtra("face_img_1", img1)
                reply.putExtra("face_img_2", img2)
                reply.putExtra("face_img_3", img3)
                setResult(RESULT_OK, reply)
                finish()
            }
        }


        btnTryAgain.setOnClickListener {
            overlayPanel.visibility = View.GONE
            dimView.visibility = View.GONE

            // Do NOT change step — recapture same position
            captured = false
            faceStableStart = 0L
        }


        /*
                btnTryAgain.setOnClickListener {
                    // hide overlay and resume analysis
                    overlayPanel.visibility = View.GONE
                    dimView.visibility = View.GONE

                    captureStep = 0
                    capturedBitmaps.clear()
                    updateStepText()

                    captured = false
                    faceStableStart = 0L

                    // nothing else needed; analyzer is still running
                }

                btnSubmit.setOnClickListener {

                    // 🔹 Must have 3 captured images
                    if (capturedBitmaps.size < 3) {
                        Toast.makeText(this, "Please complete all 3 captures", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // 🔹 Convert all 3 bitmaps to ByteArrays
                    val img1 = bitmapToBytes(capturedBitmaps[0])
                    val img2 = bitmapToBytes(capturedBitmaps[1])
                    val img3 = bitmapToBytes(capturedBitmaps[2])

                    // 🔹 Prepare reply intent
                    val reply = intent
                    reply.putExtra("face_img_1", img1)
                    reply.putExtra("face_img_2", img2)
                    reply.putExtra("face_img_3", img3)

                    // 🔹 Return to caller
                    setResult(RESULT_OK, reply)
                    finish()
                }


         */

        startCamera()
    }


    private fun bitmapToBytes(bmp: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        return stream.toByteArray()
    }



    private fun startCamera() {
        updateStepText()

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

        // 🔹 Rate-limit ML processing
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 300) {
            imageProxy.close()
            return
        }
        lastProcessTime = now

        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }

        //  Convert camera frame to upright bitmap (EXISTING NEIGHBOR CODE)
        val rotated = imageProxyToBitmapUpright(imageProxy)
        val prepared = if (MIRROR_FRONT) mirrorBitmap(rotated) else rotated

        val input = InputImage.fromBitmap(prepared, 0)

        detector.process(input)
            .addOnSuccessListener { faces ->

                // ----------------------------
                // 🔹 IF OVERLAY IS OPEN (user deciding Try Again/Submit), DO NOTHING
                // ----------------------------
                if (overlayPanel.visibility == View.VISIBLE) {
                    return@addOnSuccessListener
                }

                // ----------------------------
                // 🔹 NO FACE FOUND → RESET
                // ----------------------------
                if (faces.isEmpty()) {
                    faceStableStart = 0L
                    return@addOnSuccessListener
                }

                val faceBox = faces[0].boundingBox


                // 🔹 FACE MUST STAY STILL (same as old behavior)

                val t = System.currentTimeMillis()
                if (faceStableStart == 0L) faceStableStart = t
                val stable = t - faceStableStart > 2000   // 1.2 sec stillness (your old code)

                if (!stable) return@addOnSuccessListener


                // 🔹 CROP FACE (EXISTING CODE)

                val cropped = cropWithScale(prepared, faceBox, CROP_SCALE)


                // 🔹 NEW: BLUR CHECK (reject blurry frames)

                if (isBlurry(cropped)) {
                    Toast.makeText(this, "Face is blurry — hold still", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                // ----------------------------
                // 🔹 NEW MULTI-STEP CAPTURE
                // ----------------------------
                when (captureStep) {


                    //  STEP 0 → LEFT FACE CAPTURE
/*
                    0 -> {
                        capturedBitmaps.add(cropped)
                        captureStep = 1
                        faceStableStart = 0L
                        Toast.makeText(this, "Left captured", Toast.LENGTH_SHORT).show()

                        updateStepText()   //  HIGHLIGHT RIGHT ARROW
                    }



                    //  STEP 1 → RIGHT FACE CAPTURE

                    1 -> {
                        capturedBitmaps.add(cropped)
                        captureStep = 2
                        faceStableStart = 0L
                        Toast.makeText(this, "Right captured", Toast.LENGTH_SHORT).show()

                        updateStepText()   // ⭐ HIGHLIGHT CENTER ARROW
                    }


                    //  STEP 2 → CENTER FACE CAPTURE

                    2 -> {
                        capturedBitmaps.add(cropped)

                        // 🔹 All 3 images done → show preview UI
                        lastCaptured = cropped
                        captured = true
                        dimView.visibility = View.VISIBLE
                        overlayPanel.visibility = View.VISIBLE
                        capturedImage.setImageBitmap(cropped)

                        tvStep.text = "All photos captured"
                    }

 */

                    0 -> {
                        lastCaptured = cropped
                        showPreview(cropped)
                    }
                    1 -> {
                        lastCaptured = cropped
                        showPreview(cropped)
                    }
                    2 -> {
                        lastCaptured = cropped
                        showPreview(cropped)
                    }




                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Face detect error: ${it.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun showPreview(bmp: Bitmap) {
        dimView.visibility = View.VISIBLE
        overlayPanel.visibility = View.VISIBLE
        capturedImage.setImageBitmap(bmp)
        tvStep.text = "Preview"
    }


    private fun updateStepText() {
        when (captureStep) {
            0 -> {
                tvStep.text = "Turn LEFT and hold still"
                ivLeftArrow.alpha = 1f
                ivCenterArrow.alpha = 0.1f
                ivRightArrow.alpha = 0.1f
            }
            1 -> {
                tvStep.text = "Turn RIGHT and hold still"
                ivLeftArrow.alpha = 0.1f
                ivCenterArrow.alpha = 0.1f
                ivRightArrow.alpha = 1f
            }
            2 -> {
                tvStep.text = "Look CENTER and hold still"
                ivLeftArrow.alpha = 0.1f
                ivCenterArrow.alpha = 1f
                ivRightArrow.alpha = 0.1f
            }
        }
    }



    private fun isBlurry(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        var sum = 0
        var count = 0
        for (x in 0 until w step 10) {
            for (y in 0 until h step 10) {
                val pixel = bmp.getPixel(x, y)
                sum += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
                count++
            }
        }
        val avg = sum / (count * 3)
        return avg < 40  // low contrast → blurry
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
