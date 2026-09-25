package com.hemel.lenspilot.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import com.hemel.lenspilot.accessibility.ScreenElement
import com.hemel.lenspilot.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * TIER 2/3 fallback: used whenever the accessibility tree isn't the active
 * source — either because the user hasn't enabled Accessibility, or because
 * they've explicitly switched Settings to "স্ক্রিন VLM"
 * (Prefs.SCREEN_GUIDE_MODE_VLM). Captures one screenshot via
 * [ScreenCaptureService] (MediaProjection), then:
 *
 *  1. Runs on-device OCR (ML Kit) — this alone labels most elements, since
 *     most icons already show their name as text underneath.
 *  2. Finds+labels the remaining icon/button-shaped regions using ONE of
 *     three interchangeable engines, picked by Prefs.screenGuideMode:
 *       - ACCESSIBILITY (default whenever this manager runs at all — i.e.
 *         Accessibility permission is simply missing) or YOLO (explicit
 *         user opt-in, forces this whole fallback pipeline even when
 *         Accessibility IS granted): the on-device [IconDetector] — fast,
 *         offline, zero network cost, fixed 188-class vocabulary
 *         (ui_detector_w8a32.tflite, a YOLO-style single-pass detector).
 *       - VLM (explicit user opt-in from Settings, forces this whole
 *         fallback pipeline even when Accessibility IS granted): the
 *         server-side Screen VLM (/api/vision/screen-elements,
 *         Florence-2 on the Space) — open-vocabulary, needs network, is
 *         noticeably slower per frame, but isn't limited to a fixed icon
 *         list.
 *       - OCR (explicit user opt-in): skips this step entirely — the
 *         element list is whatever step 1's OCR found, nothing else.
 *         Cheapest/fastest option, best for text-heavy screens.
 *  3. Drops any icon box that sits right on top of an OCR text block —
 *     that text IS the better label, no need for two overlapping
 *     elements.
 *
 * If the chosen engine fails for one frame (VLM: offline/timeout; on-
 * device: an exception from the interpreter) this silently degrades to
 * OCR-only elements for that frame rather than blocking guidance
 * entirely.
 */
class VisionFallbackManager(private val context: Context) {

    companion object {
        private const val TAG = "VisionFallbackManager"

        // How far (in px) an OCR text block may sit from an icon box and
        // still count as "this element's real label" — tuned for typical
        // mdpi/hdpi icon+caption layouts (e.g. bottom nav, grid icons).
        private const val LABEL_SEARCH_MARGIN_PX = 60
        private const val JPEG_QUALITY = 80
    }

    private var projectionData: Intent? = null

    // Only ever built if the on-device engine is actually used this run —
    // no model load cost paid when the user has picked Screen VLM. Kept as
    // a named Lazy (rather than a bare `by lazy` property) so release()
    // can check isInitialized() without forcing a build.
    private val iconDetectorLazy: Lazy<IconDetector> = lazy { IconDetector(context) }
    private val iconDetector: IconDetector by iconDetectorLazy

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
     * capture service isn't up. All work runs off the main thread. */
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

        // --- Step 2: icon/button regions, from whichever engine Settings
        // picked. OCR mode skips this step entirely on purpose. ---
        val mode = Prefs.screenGuideMode(context)
        val iconBoxes: List<LabeledBox> = if (mode == Prefs.SCREEN_GUIDE_MODE_OCR) {
            emptyList()
        } else {
            val useVlm = mode == Prefs.SCREEN_GUIDE_MODE_VLM
            try {
                if (useVlm) fetchVlmElements(bitmap) else detectOnDevice(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "Icon detection failed (${if (useVlm) "vlm" else "on-device"}), continuing with OCR-only elements", e)
                emptyList()
            }
        }

        // --- Step 3: drop icon boxes that duplicate an OCR text block ---
        for (box in iconBoxes) {
            if (findNearbyOcrLabel(box.bbox, ocrBlocks) != null) continue
            elements.add(
                ScreenElement(
                    id = "icon_${counter++}",
                    type = "icon",
                    label = box.label,
                    bbox = box.bbox,
                    clickable = true
                )
            )
        }

        elements
    }

    private data class LabeledBox(val label: String, val bbox: Rect)

    private fun detectOnDevice(bitmap: Bitmap): List<LabeledBox> =
        iconDetector.detect(bitmap).map { LabeledBox(it.label, it.bbox) }

    private suspend fun fetchVlmElements(bitmap: Bitmap): List<LabeledBox> {
        val baseUrl = context.getString(R.string.space_base_url)
        val imageBase64 = bitmapToBase64(bitmap)
        val response = ApiClient.screenVlmElements(context, baseUrl, imageBase64).getOrThrow()
        val elementsJson = response.optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<LabeledBox>()
        for (i in 0 until elementsJson.length()) {
            val el = elementsJson.optJSONObject(i) ?: continue
            val bboxArr = el.optJSONArray("bbox") ?: continue
            if (bboxArr.length() != 4) continue
            val x = bboxArr.optInt(0)
            val y = bboxArr.optInt(1)
            val w = bboxArr.optInt(2)
            val h = bboxArr.optInt(3)
            if (w <= 0 || h <= 0) continue
            val label = el.optString("label", "element")
            out.add(LabeledBox(label, Rect(x, y, x + w, y + h)))
        }
        return out
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /** Looks for an OCR text block directly below, above, or overlapping
     * the given box - the common "icon + caption" pattern, or a box that
     * just re-detected text ML Kit already read - within
     * [LABEL_SEARCH_MARGIN_PX]. Returns that text as the label if found. */
    private fun findNearbyOcrLabel(box: Rect, ocrBlocks: List<OcrTextBlock>): String? {
        var best: OcrTextBlock? = null
        var bestDistance = Int.MAX_VALUE

        for (block in ocrBlocks) {
            val horizontallyAligned = block.bbox.left < box.right + LABEL_SEARCH_MARGIN_PX &&
                block.bbox.right > box.left - LABEL_SEARCH_MARGIN_PX
            if (!horizontallyAligned) continue

            val overlaps = Rect.intersects(block.bbox, box)
            val verticalGap = when {
                overlaps -> 0
                block.bbox.top >= box.bottom -> block.bbox.top - box.bottom       // caption below
                block.bbox.bottom <= box.top -> box.top - block.bbox.bottom       // caption above
                else -> 0
            }
            if (verticalGap <= LABEL_SEARCH_MARGIN_PX && verticalGap < bestDistance) {
                bestDistance = verticalGap
                best = block
            }
        }
        return best?.text
    }

    fun release() {
        // Only close the on-device interpreter if it was actually built —
        // checking isInitialized() (rather than just touching
        // `iconDetector`) avoids forcing a pointless model load+close when
        // this run only ever used Screen VLM.
        if (iconDetectorLazy.isInitialized()) {
            iconDetector.close()
        }
    }
}
