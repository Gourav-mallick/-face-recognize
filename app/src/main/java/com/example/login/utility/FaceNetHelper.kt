package com.example.login.utility

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

class FaceNetHelper(context: Context) {
    private val interpreter: Interpreter

    init {
        val assetManager = context.assets
        val model = loadModelFile(assetManager, "facenet.tflite")
        interpreter = Interpreter(model)
    }

    private fun loadModelFile(assetManager: AssetManager, filename: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun getFaceEmbedding(bitmap: Bitmap): FloatArray {
        val input = preprocess(bitmap)
        val output = Array(1) { FloatArray(128) }
        interpreter.run(input, output)
        return normalize(output[0])
    }

    private fun preprocess(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val resized = Bitmap.createScaledBitmap(bitmap, 160, 160, true)
        val input = Array(1) { Array(160) { Array(160) { FloatArray(3) } } }

        for (y in 0 until 160) {
            for (x in 0 until 160) {
                val pixel = resized.getPixel(x, y)

                val r = (Color.red(pixel) - 127.5f) / 128f
                val g = (Color.green(pixel) - 127.5f) / 128f
                val b = (Color.blue(pixel) - 127.5f) / 128f

                input[0][y][x][0] = r
                input[0][y][x][1] = g
                input[0][y][x][2] = b
            }
        }

        return input
    }

    // Normalize embedding to unit length
    // Normalize embedding to unit length
    private fun normalize(embedding: FloatArray): FloatArray {
        val norm = sqrt(embedding.map { it * it }.sum().toFloat())
        return embedding.map { it / norm }.toFloatArray()
    }

    // Euclidean Distance
    fun calculateDistance(e1: FloatArray, e2: FloatArray): Float {
        val sum = e1.zip(e2) { a, b -> (a - b).pow(2) }.sum()
        return sqrt(sum.toFloat())
    }

    // Optional: Cosine similarity
    fun cosineSimilarity(e1: FloatArray, e2: FloatArray): Float {
        val dot = e1.zip(e2) { a, b -> a * b }.sum()
        val norm1 = sqrt(e1.map { it * it }.sum().toFloat())
        val norm2 = sqrt(e2.map { it * it }.sum().toFloat())
        return (dot / (norm1 * norm2)).toFloat()
    }

}
