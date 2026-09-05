package com.hemel.lenspilot.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class ClassificationResult(val label: String, val confidence: Float)

/**
 * Runs classifier_best_fp16.tflite (128x128 input, 168 icon-type classes).
 * Called ONLY for detector "Icon" boxes that had no matching OCR text
 * label nearby — on a typical screen that's a handful of boxes at most,
 * so this stays cheap even though the model itself is tiny already.
 */
class IconClassifier(context: Context) {

    companion object {
        private const val TAG = "IconClassifier"
        private const val INPUT_SIZE = 128
        private const val MIN_CONFIDENCE = 0.4f
    }

    private var nnApiDelegate: NnApiDelegate? = null
    private val interpreter: Interpreter by lazy { buildInterpreter(context) }

    private fun buildInterpreter(context: Context): Interpreter {
        val model = loadModelFile(context, "classifier_best_fp16.tflite")
        return try {
            val delegate = NnApiDelegate()
            nnApiDelegate = delegate
            Interpreter(model, Interpreter.Options().addDelegate(delegate).setNumThreads(2))
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI delegate unavailable, falling back to CPU", e)
            nnApiDelegate?.close()
            nnApiDelegate = null
            Interpreter(model, Interpreter.Options().setNumThreads(2))
        }
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        val afd = context.assets.openFd(assetName)
        val channel = afd.createInputStream().channel
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /** [crop] should already be roughly the icon's bounding box (some
     * padding helps accuracy). Returns null if confidence is too low to trust. */
    fun classify(crop: Bitmap): ClassificationResult? {
        val resized = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToInputBuffer(resized)
        val output = Array(1) { FloatArray(IconLabels.CLASSIFIER_CLASSES.size) }
        interpreter.run(input, output)

        var bestIdx = -1
        var bestScore = 0f
        for (i in output[0].indices) {
            if (output[0][i] > bestScore) {
                bestScore = output[0][i]
                bestIdx = i
            }
        }
        if (bestIdx < 0 || bestScore < MIN_CONFIDENCE) return null
        return ClassificationResult(IconLabels.CLASSIFIER_CLASSES[bestIdx], bestScore)
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).apply { order(ByteOrder.nativeOrder()) }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255f)
            buffer.putFloat((p and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    fun close() {
        interpreter.close()
        nnApiDelegate?.close()
    }
}
