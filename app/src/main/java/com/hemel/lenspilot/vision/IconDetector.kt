package com.hemel.lenspilot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class Detection(val bbox: Rect, val classIndex: Int, val confidence: Float)

/**
 * Runs detector_best_fp16.tflite (Ultralytics YOLOv8n export, 640x640,
 * raw NMS-free output [1, 25, 8400] = 4 bbox coords + 21 class scores).
 *
 * One Interpreter instance is created once and reused for every screenshot
 * — recreating it per-frame was the main cause of the earlier slowness.
 * NNAPI is tried first (uses the device NPU/GPU where available, adds no
 * extra APK size since it ships inside the base tensorflow-lite artifact);
 * it silently falls back to CPU-only if NNAPI isn't usable on the device.
 */
class IconDetector(context: Context) {

    companion object {
        private const val TAG = "IconDetector"
        private const val INPUT_SIZE = 640
        private val NUM_CLASSES = IconLabels.DETECTOR_CLASSES.size // 21 — not a compile-time constant (array .size), so can't be `const`
        private const val NUM_ANCHORS = 8400
        private const val CONF_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
    }

    private var nnApiDelegate: NnApiDelegate? = null
    private val interpreter: Interpreter by lazy { buildInterpreter(context) }

    private fun buildInterpreter(context: Context): Interpreter {
        val model = loadModelFile(context, "detector_best_fp16.tflite")
        return try {
            val delegate = NnApiDelegate()
            nnApiDelegate = delegate
            val options = Interpreter.Options().addDelegate(delegate).setNumThreads(4)
            Interpreter(model, options)
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI delegate unavailable, falling back to CPU", e)
            nnApiDelegate?.close()
            nnApiDelegate = null
            Interpreter(model, Interpreter.Options().setNumThreads(4))
        }
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        val afd = context.assets.openFd(assetName)
        val inputStream = afd.createInputStream()
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /** Letterbox-resizes [bitmap] to 640x640, runs inference, decodes boxes
     * back into the original bitmap's pixel coordinates. Runs on whichever
     * thread it's called from — caller should already be off the main thread. */
    fun detect(bitmap: Bitmap): List<Detection> {
        val t0 = SystemClock.elapsedRealtime()
        val (letterboxed, scale, padX, padY) = letterbox(bitmap, INPUT_SIZE)

        val input = bitmapToInputBuffer(letterboxed)
        // Shape [1, 25, 8400]
        val output = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(NUM_ANCHORS) } }
        interpreter.run(input, output)

        val candidates = mutableListOf<Detection>()
        for (i in 0 until NUM_ANCHORS) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val score = output[0][4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < CONF_THRESHOLD || bestClass < 0) continue

            val cx = output[0][0][i]
            val cy = output[0][1][i]
            val w = output[0][2][i]
            val h = output[0][3][i]

            // Undo letterbox: from 640-space back to the original bitmap
            val left = ((cx - w / 2f) - padX) / scale
            val top = ((cy - h / 2f) - padY) / scale
            val right = ((cx + w / 2f) - padX) / scale
            val bottom = ((cy + h / 2f) - padY) / scale

            val rect = Rect(
                left.toInt().coerceIn(0, bitmap.width),
                top.toInt().coerceIn(0, bitmap.height),
                right.toInt().coerceIn(0, bitmap.width),
                bottom.toInt().coerceIn(0, bitmap.height)
            )
            if (rect.width() <= 1 || rect.height() <= 1) continue
            candidates.add(Detection(rect, bestClass, bestScore))
        }

        val result = classAwareNms(candidates)
        Log.d(TAG, "detect(): ${result.size} boxes in ${SystemClock.elapsedRealtime() - t0}ms")
        return result
    }

    private fun classAwareNms(boxes: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (classIdx in 0 until NUM_CLASSES) {
            val ofClass = boxes.filter { it.classIndex == classIdx }.sortedByDescending { it.confidence }.toMutableList()
            while (ofClass.isNotEmpty()) {
                val best = ofClass.removeAt(0)
                kept.add(best)
                ofClass.removeAll { iou(it.bbox, best.bbox) > IOU_THRESHOLD }
            }
        }
        return kept
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea = maxOf(0, interRight - interLeft) * maxOf(0, interBottom - interTop)
        val union = a.width() * a.height() + b.width() * b.height() - interArea
        return if (union <= 0) 0f else interArea.toFloat() / union.toFloat()
    }

    /** Returns (letterboxedBitmap, scale, padX, padY). */
    private fun letterbox(src: Bitmap, targetSize: Int): LetterboxResult {
        val scale = minOf(targetSize.toFloat() / src.width, targetSize.toFloat() / src.height)
        val newW = (src.width * scale).toInt()
        val newH = (src.height * scale).toInt()
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        val canvas = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(canvas)
        c.drawColor(android.graphics.Color.rgb(114, 114, 114)) // standard YOLO letterbox grey
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(padX, padY)
        }
        c.drawBitmap(src, matrix, null)
        return LetterboxResult(canvas, scale, padX, padY)
    }

    // data class already synthesizes component1..4 for destructuring, no extra code needed
    private data class LetterboxResult(val bitmap: Bitmap, val scale: Float, val padX: Float, val padY: Float)

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).apply { order(ByteOrder.nativeOrder()) }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255f) // R
            buffer.putFloat(((p shr 8) and 0xFF) / 255f)  // G
            buffer.putFloat((p and 0xFF) / 255f)          // B
        }
        buffer.rewind()
        return buffer
    }

    fun close() {
        interpreter.close()
        nnApiDelegate?.close()
    }
}
