package com.hemel.lenspilot.accessibility

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/**
 * One tappable/readable thing found on screen — matches the "elements"
 * schema the Space's /api/analyze-screen endpoint expects:
 *   {"id": "...", "type": "button|text|icon|input", "label": "...",
 *    "bbox": [x, y, w, h], "clickable": true}
 */
data class ScreenElement(
    val id: String,
    val type: String,
    val label: String,
    val bbox: Rect,     // absolute screen pixels
    val clickable: Boolean
)

fun List<ScreenElement>.toAnalyzeScreenJson(): JSONArray {
    val array = JSONArray()
    for (el in this) {
        val obj = JSONObject()
        obj.put("id", el.id)
        obj.put("type", el.type)
        obj.put("label", el.label)
        val bboxArr = JSONArray()
        bboxArr.put(el.bbox.left)
        bboxArr.put(el.bbox.top)
        bboxArr.put(el.bbox.width())
        bboxArr.put(el.bbox.height())
        obj.put("bbox", bboxArr)
        obj.put("clickable", el.clickable)
        array.put(obj)
    }
    return array
}
