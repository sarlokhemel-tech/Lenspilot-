package com.hemel.lenspilot.overlay

import android.graphics.Color
import android.graphics.Rect
import org.json.JSONObject

data class Highlight(
    val elementId: String,
    val bbox: Rect,       // absolute screen pixels
    val color: Int,       // parsed ARGB
    val actionHint: String,
    val label: String
)

/** Parses the JSON body /api/analyze-screen returns into drawable highlights. */
data class AnalyzeScreenResult(
    val guidanceText: String,
    val highlights: List<Highlight>,
    val workflowStep: Int,
    val workflowTotal: Int,
    val errorDetected: Boolean,
    val errorSolution: String?
)

fun parseAnalyzeScreenResponse(json: String): AnalyzeScreenResult {
    val obj = JSONObject(json)
    val highlightsArr = obj.optJSONArray("highlights")
    val highlights = mutableListOf<Highlight>()
    if (highlightsArr != null) {
        for (i in 0 until highlightsArr.length()) {
            val h = highlightsArr.getJSONObject(i)
            val bboxArr = h.optJSONArray("bbox")
            val bbox = if (bboxArr != null && bboxArr.length() == 4) {
                val x = bboxArr.getDouble(0).toInt()
                val y = bboxArr.getDouble(1).toInt()
                val w = bboxArr.getDouble(2).toInt()
                val hgt = bboxArr.getDouble(3).toInt()
                Rect(x, y, x + w, y + hgt)
            } else {
                Rect(0, 0, 0, 0)
            }
            val colorInt = try {
                Color.parseColor(h.optString("color", "#2563EB"))
            } catch (e: IllegalArgumentException) {
                Color.parseColor("#2563EB")
            }
            highlights.add(
                Highlight(
                    elementId = h.optString("element_id", ""),
                    bbox = bbox,
                    color = colorInt,
                    actionHint = h.optString("action_hint", "tap"),
                    label = h.optString("label", "")
                )
            )
        }
    }
    return AnalyzeScreenResult(
        guidanceText = obj.optString("guidance_text", ""),
        highlights = highlights,
        workflowStep = obj.optInt("workflow_step", 1),
        workflowTotal = obj.optInt("workflow_total", 1),
        errorDetected = obj.optBoolean("error_detected", false),
        errorSolution = if (obj.isNull("error_solution")) null else obj.optString("error_solution", null)
    )
}
