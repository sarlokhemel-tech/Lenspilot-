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
    val clickable: Boolean,
    // কমা-দেওয়া ছোট অবস্থা-শব্দ: checked, selected, disabled, focused, password, empty, unnamed
    val flags: String = "",
    // true = পপআপ/ডায়ালগ/ওপরের স্তরের উইন্ডোর জিনিস (সার্ভার এগুলোকে আগে সামলাতে বলে)
    val popup: Boolean = false,
    // label থেকে আলাদা contentDescription (দুটো ভিন্ন হলে দুটোই যায়)
    val desc: String = "",
    // যে কনটেইনারের ভেতরে এটা (নিকটতম ক্লিকেবল/তালিকা-অভিভাবকের id) — সারি/কার্ডের গঠন বোঝার জন্য
    val parentId: String = ""
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
        if (el.flags.isNotEmpty()) obj.put("flags", el.flags)
        if (el.popup) obj.put("win", "popup")
        if (el.desc.isNotEmpty()) obj.put("desc", el.desc)
        if (el.parentId.isNotEmpty()) obj.put("in", el.parentId)
        array.put(obj)
    }
    return array
}
