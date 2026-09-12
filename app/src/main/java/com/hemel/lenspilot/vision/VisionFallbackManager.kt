package com.hemel.lenspilot.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResultLauncher
import com.hemel.lenspilot.accessibility.ScreenElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TIER 2/3 fallback: used only when the user has NOT enabled Accessibility
 * (the "lite app" / browser path). Captures one screenshot via
 * [ScreenCaptureService] (MediaProjection), then:
 *
 *  1. Runs on-device OCR (ML Kit) - this alone labels most elements, since
 *     most icons already show their name as text underneath.
 *  2. Runs the YOLO icon/UI-element detector to find "Icon" boxes.
 *  3. For each Icon box, first check whether an OCR text block sits right
 *     next to/under it - if so, that text IS the label and classification
 *     is skipped entirely (fast path, covers the majority of icons).
 *  4. Only icons with NO nearby OCR label (e.g. unlabeled social-media
 *     icons) get cropped and run through the small classifier model.
 *
 * This keeps the classifier off the hot path for most screens - it only
 * ever runs on the handful of icons that actually need it.
 */
class VisionFallbackManager(private val context: Context) {

    companion object {
        // How far (in px) an OCR text block may sit from an icon box and
        // still count as "this icon's label" - tuned for typical mdpi/hdpi
        // icon+caption layouts (e.g. bottom nav, grid icons with captions).
        private const val LABEL_SEARCH_MARGIN_PX = 60
        private const val CROP_PADDING_PX = 6
    }

    private var projectionData: Intent? = null

    private val iconDetector: IconDetector by lazy { IconDetector(context) }
    private val iconClassifier: IconClassifier by lazy { IconClassifier(context) }

    val hasScreenCapturePermission: Boolean get() = projectionData != null

    /** Call once (e.g. the first time the user tries the fallback path
     * without Accessibility enabled) - shows the OS's one-time screen
     * capture consent dialog. */
    fun requestScreenCapturePermission(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }

    /** Feed this the ActivityResult callback's (resultCode, data) after
     * [requestScreenCapturePermission]'s launcher returns, then start the
     * capture service once. Consent persists for the rest of this app
     * process - no need to ask again until the process restarts. */
    fun onScreenCapturePermissionResult(resultCode: Int, data: Intent?) {
        if (data == null) return
        projectionData = data
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        context.startForegroundService(intent)
    }

    /** Captures the current screen and returns a combined OCR + icon
     * element list - empty if permission hasn't been granted yet or the
     * capture service isn't up. All model inference runs off the main thread. */
    suspend fun captureScreenElements(): List<ScreenElement> = withContext(Dispatchers.Default) {
        val service = ScreenCaptureService.instance ?: return@withContext emptyList()
        val bitmap = service.captureFrame() ?: return@withContext emptyList()

        val elements = mutableListOf<ScreenElement>()
        var counter = 0

        // --- Step 1: OCR (covers most elements - icons with a caption,
        // buttons, text fields, everything with visible text) ---
        val ocrBlocks = TextOcr.recognize(bitmap)
        for (t in ocrBlocks) {
            elements.add(
                ScreenElement(
                    id = "ocr_${counter++}",
                    type = "text",
                    label = t.text,
                    bbox = t.bbox,
                    clickable = false
                )
            )
        }

        // --- Step 2: icon detection ---
        val detections = try {
            iconDetector.detect(bitmap)
        } catch (e: Exception) {
            emptyList()
        }

        // --- Step 3/4: only bare "Icon" boxes need a label at all, and
        // only classify the ones OCR didn't already label ---
        for (d in detections) {
            if (d.classIndex != IconLabels.DETECTOR_ICON_CLASS_INDEX) continue

            val ocrLabel = findNearbyOcrLabel(d.bbox, ocrBlocks)
            val label = ocrLabel ?: classifyCrop(bitmap, d.bbox)?.label ?: continue // skip icons we truly can't label

            elements.add(
                ScreenElement(
                    id = "icon_${counter++}",
                    type = "icon",
                    label = label,
                    bbox = d.bbox,
                    clickable = true
                )
            )
        }

        elements
    }

    /** Looks for an OCR text block directly below, above, or overlapping
     * the icon box - the common "icon + caption" pattern - within
     * [LABEL_SEARCH_MARGIN_PX]. Returns that text as the label if found. */
    private fun findNearbyOcrLabel(iconBox: Rect, ocrBlocks: List<OcrTextBlock>): String? {
        var best: OcrTextBlock? = null
        var bestDistance = Int.MAX_VALUE

        for (block in ocrBlocks) {
            val horizontallyAligned = block.bbox.left < iconBox.right + LABEL_SEARCH_MARGIN_PX &&
                block.bbox.right > iconBox.left - LABEL_SEARCH_MARGIN_PX
            if (!horizontallyAligned) continue

            val verticalGap = when {
                block.bbox.top >= iconBox.bottom -> block.bbox.top - iconBox.bottom       // caption below
                block.bbox.bottom <= iconBox.top -> iconBox.top - block.bbox.bottom       // caption above
                else -> 0                                                                  // overlapping
            }
            if (verticalGap <= LABEL_SEARCH_MARGIN_PX && verticalGap < bestDistance) {
                bestDistance = verticalGap
                best = block
            }
        }
        return best?.text
    }

    private fun classifyCrop(bitmap: Bitmap, box: Rect): ClassificationResult? {
        val padded = Rect(
            (box.left - CROP_PADDING_PX).coerceAtLeast(0),
            (box.top - CROP_PADDING_PX).coerceAtLeast(0),
            (box.right + CROP_PADDING_PX).coerceAtMost(bitmap.width),
            (box.bottom + CROP_PADDING_PX).coerceAtMost(bitmap.height)
        )
        if (padded.width() <= 1 || padded.height() <= 1) return null
        val crop = Bitmap.createBitmap(bitmap, padded.left, padded.top, padded.width(), padded.height())
        return try {
            iconClassifier.classify(crop)
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        iconDetector.close()
        iconClassifier.close()
    }
}
