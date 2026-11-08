package com.hemel.lenspilot.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrTextBlock(val text: String, val bbox: Rect)

/**
 * Tier 2 fallback: on-device text recognition (ML Kit — bundled model,
 * no network, no separate download).
 *
 * ⚠️ Known limitation, worth being upfront about: ML Kit's Text
 * Recognition v2 officially supports Latin, Chinese, Devanagari, Japanese,
 * and Korean scripts — Bengali script is NOT in that list. In practice
 * this means English UI labels (Settings, Wi-Fi, OK, Cancel...) OCR
 * reliably, but Bengali app text will often come back empty, garbled, or
 * partially wrong. Combined with Tier 3 (icon shapes/positions don't
 * depend on any script) and Tier 4 (a raw screenshot straight to Gemini,
 * which reads Bengali far better than any on-device OCR), this is fine as
 * a middle fallback rather than the final answer — just don't expect
 * Bengali text elements to be as reliable here as the icon boxes are.
 */
object TextOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val blocks = mutableListOf<OcrTextBlock>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val text = line.text.trim()
                        if (text.isNotEmpty()) blocks.add(OcrTextBlock(text, box))
                    }
                }
                if (cont.isActive) cont.resume(blocks)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }
}
