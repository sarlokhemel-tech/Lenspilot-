package com.hemel.lenspilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject

/**
 * Reads the screen for the AI in a deliberately small, "what a person sees"
 * form instead of dumping every node:
 *
 *  - every CLICKABLE thing (clickable / long-clickable / checkable / editable),
 *    each with the least text that identifies it — own label, else the text
 *    of what is inside it, else its resource-id name, else "(আইকন)". An
 *    icon with no label is still listed (the old reader dropped those, so
 *    the model could never "see" them);
 *  - popups / dialogs / other app windows above the base window, with ALL
 *    their text, listed first so a truncated list never cuts them;
 *  - a handful of headline texts and the last toast/snackbar text, so
 *    "something popped up" is never invisible;
 *  - a tiny meta line (app, window title, keyboard open, can-scroll).
 *
 * Plain decorative text is NOT sent — that noise was making the model
 * hallucinate targets. The same pass yields a fingerprint of the
 * interactive layout used to decide whether the screen really changed.
 */
class ScreenReader(private val service: AccessibilityService) {

    data class Snapshot(
        val elements: List<ScreenElement>,
        val meta: JSONObject,
        val fingerprint: Int
    )

    private class Raw(
        val type: String, val label: String, val bbox: Rect, val clickable: Boolean,
        val flags: String, val popup: Boolean, val order: Int,
        val desc: String = "", val parent: Raw? = null
    )

    private var screenW = 0
    private var screenH = 0
    private var nodeBudget = 0
    private var skippedStale = 0

    fun read(notice: String? = null): Snapshot {
        val dm = service.resources.displayMetrics
        screenW = dm.widthPixels; screenH = dm.heightPixels
        nodeBudget = 50_000; skippedStale = 0   // শুধু অসীম লুপ ঠেকানোর নিরাপত্তা, বাস্তবে কখনো ছোঁয় না

        val raws = ArrayList<Raw>()
        var basePkg = ""
        var title = ""
        var keyboard = false
        var popupPresent = false
        var canDown = false
        var canUp = false
        val ownPkg = service.packageName

        val roots = ArrayList<Triple<AccessibilityNodeInfo, Boolean, String>>() // root, popup?, title
        try {
            val wins = service.windows ?: emptyList()
            val usable = wins.filter { w ->
                when (w.type) {
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> { keyboard = true; false }
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> false
                    else -> true
                }
            }.sortedByDescending { it.layer }
            var baseSeen = false
            // walk bottom-up to find the base app window, then mark everything above it as popup
            val ordered = usable.sortedBy { it.layer }
            for (w in ordered) {
                val root = try { w.root } catch (e: Exception) { null } ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (pkg == ownPkg && w.type != AccessibilityWindowInfo.TYPE_APPLICATION) { continue } // our own overlays
                if (pkg == "com.android.systemui" && !w.isActive && !w.isFocused) continue
                val isApp = w.type == AccessibilityWindowInfo.TYPE_APPLICATION
                val popup = baseSeen
                if (isApp && !baseSeen) { baseSeen = true; basePkg = pkg; title = w.title?.toString() ?: "" }
                else if (!baseSeen) { /* system window under any app, e.g. launcher-less */ if (basePkg.isEmpty()) { basePkg = pkg; title = w.title?.toString() ?: "" } }
                roots += Triple(root, popup, w.title?.toString() ?: "")
            }
        } catch (e: Exception) { /* fall through to active-window fallback */ }

        if (roots.isEmpty()) {
            val r = try { service.rootInActiveWindow } catch (e: Exception) { null }
            if (r != null) {
                basePkg = r.packageName?.toString() ?: ""
                roots += Triple(r, false, "")
            }
        }
        // topmost window first so a truncated list never cuts popups
        roots.reverse()

        var order = 0
        val plainBudget = intArrayOf(Int.MAX_VALUE)
        val seen = HashSet<String>()
        for ((root, popup, _) in roots) {
            if (popup) popupPresent = true
            order = walk(root, popup, popup, 0, null, raws, seen, plainBudget, order,
                flagsOut = { down, up -> if (down) canDown = true; if (up) canUp = true })
        }

        // popups first, then reading order (top→bottom, left→right)
        val sorted = raws.sortedWith(compareByDescending<Raw> { it.popup }
            .thenBy { it.bbox.top / 24 }.thenBy { it.bbox.left })
        val kept = sorted

        val idOf = HashMap<Raw, String>(kept.size * 2)
        kept.forEachIndexed { i, r -> idOf[r] = "el_$i" }
        val out = ArrayList<ScreenElement>(kept.size)
        kept.forEachIndexed { i, r ->
            out += ScreenElement("el_$i", r.type, r.label, r.bbox, r.clickable, r.flags, r.popup,
                r.desc, r.parent?.let { idOf[it] } ?: "")
        }

        val meta = JSONObject().apply {
            put("pkg", basePkg); if (title.isNotBlank()) put("title", title.take(60))
            put("w", screenW); put("h", screenH)
            if (popupPresent) put("popup", true)
            if (keyboard) put("keyboard", true)
            put("scroll", when { canDown && canUp -> "both"; canDown -> "down"; canUp -> "up"; else -> "none" })
            if (!notice.isNullOrBlank()) put("notice", notice)
        }

        // fingerprint: পুরো স্ক্রিনের গঠন + লেখা — তবে সংখ্যা বাদ (ঘড়ি/কাউন্টার/টাইমার যেন বারবার "নতুন স্ক্রিন" না ভাবায়) আর টাইপ-করা মান বাদ
        val fp = StringBuilder(basePkg).append('|').append(popupPresent)
        for (e in out) {
            val lbl = (if (e.type == "input") e.label.substringBefore(" = \"") else e.label).filterNot { it.isDigit() }
            fp.append('|').append(lbl).append('@')
                .append(e.bbox.centerX() / 48).append(',').append(e.bbox.centerY() / 48)
        }
        return Snapshot(out, meta, fp.toString().hashCode())
    }

    private fun walk(
        node: AccessibilityNodeInfo, windowPopup: Boolean, inPopup: Boolean, depth: Int,
        parent: Raw?, out: MutableList<Raw>, seen: MutableSet<String>,
        plainBudget: IntArray, order0: Int, flagsOut: (Boolean, Boolean) -> Unit
    ): Int {
        var order = order0
        if (nodeBudget-- <= 0 || depth > 200) return order
        try {
            if (!node.isVisibleToUser) return order
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.width() <= 0 || b.height() <= 0) return order
            if (b.right <= 0 || b.bottom <= 0 || b.left >= screenW || b.top >= screenH) return order

            val cls = node.className?.toString() ?: ""
            val dialogish = (Build.VERSION.SDK_INT >= 28 && node.paneTitle != null) || cls.contains("Dialog") || cls.contains("Alert") ||
                cls.contains("Snackbar") || cls.contains("Toast") || cls.contains("Popup")
            val popup = inPopup || dialogish

            val interactive = node.isClickable || node.isLongClickable || node.isCheckable || node.isEditable
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            var me: Raw? = null

            if (node.isScrollable) {
                val ids = node.actionList.map { it.id }
                val down = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids
                val up = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids
                flagsOut(down, up)
                if (!interactive) {
                    val fl = ArrayList<String>(2)
                    if (down) fl += "scroll_down"
                    if (up) fl += "scroll_up"
                    val key = "${b.left},${b.top},${b.right},${b.bottom}|list"
                    if (seen.add(key)) {
                        val r = Raw("list", text.ifEmpty { desc }.ifEmpty { "(স্ক্রল-এলাকা)" }, b, false,
                            fl.joinToString(","), popup, order++, "", parent)
                        out += r; me = r
                    }
                }
            }

            if (interactive) {
                val editable = node.isEditable
                var label = when {
                    editable -> (node.hintText?.toString()?.trim().orEmpty().ifBlank { desc }.ifBlank { "ইনপুট" })
                    text.isNotEmpty() -> text
                    desc.isNotEmpty() -> desc
                    else -> childText(node)
                }
                val flags = ArrayList<String>(4)
                if (label.isBlank()) {
                    val idTail = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    label = if (idTail != null) "[$idTail]" else "(আইকন)"
                    flags += "unnamed"
                }
                if (editable) {
                    if (node.isPassword) flags += "password"
                    else if (text.isEmpty() || text == node.hintText?.toString()) flags += "empty"
                    else label += " = \"$text\""
                }
                if (!node.isClickable && node.isLongClickable) flags += "longclick_only"
                if (node.isScrollable) flags += "scrollable"
                if (node.isChecked) flags += "checked"
                if (node.isSelected) flags += "selected"
                if (!node.isEnabled) flags += "disabled"
                if (node.isFocused) flags += "focused"
                val type = when {
                    editable -> "input"
                    node.isCheckable || cls.contains("Switch") || cls.contains("CheckBox") -> "toggle"
                    "unnamed" in flags || cls.contains("Image") -> "icon"
                    else -> "button"
                }
                // contentDescription label থেকে ভিন্ন হলে আলাদা field-এ যায় — কিছু হারায় না
                val extraDesc = if (desc.isNotEmpty() && !label.startsWith(desc) && desc != text) desc else ""
                val key = "${b.left},${b.top},${b.right},${b.bottom}|$label"
                if (seen.add(key)) {
                    val r = Raw(type, label, b, true, flags.joinToString(","), popup, order++, extraDesc, parent)
                    out += r; me = r
                }
            } else {
                // অ-ক্লিকযোগ্য: স্ক্রিনে যা-ই দেখা যায় (লেখা বা বর্ণনা) সবই যায়, কোনো সীমা/বাদ নেই
                val t = text.ifEmpty { desc }
                if (t.isNotEmpty()) {
                    val key = "${b.left},${b.top},${b.right},${b.bottom}|$t"
                    if (seen.add(key)) {
                        val type = if (text.isEmpty() && cls.contains("Image")) "image" else "text"
                        val extraDesc = if (text.isNotEmpty() && desc.isNotEmpty() && desc != text) desc else ""
                        out += Raw(type, t, b, false, if (!node.isEnabled) "disabled" else "", popup, order++, extraDesc, parent)
                    }
                }
            }

            val childParent = me ?: parent
            for (i in 0 until node.childCount) {
                val c = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                order = walk(c, windowPopup, popup, depth + 1, childParent, out, seen, plainBudget, order, flagsOut)
            }
        } catch (e: IllegalStateException) {
            skippedStale++ // node went stale mid-walk — same guard the old reader had
        }
        return order
    }

    /** Shortest useful label from the non-interactive texts inside a clickable container. */
    private fun childText(node: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>(3)
        fun rec(n: AccessibilityNodeInfo, d: Int) {
            if (d > 6) return
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
                try {
                    if (!c.isVisibleToUser) continue
                    val inner = c.isClickable || c.isCheckable || c.isEditable
                    if (!inner) {
                        val t = (c.text?.toString() ?: c.contentDescription?.toString() ?: "").trim()
                        if (t.isNotEmpty()) parts += t
                    }
                    if (!inner) rec(c, d + 1)
                } catch (e: IllegalStateException) { }
            }
        }
        rec(node, 0)
        return parts.joinToString(" · ")
    }
}
