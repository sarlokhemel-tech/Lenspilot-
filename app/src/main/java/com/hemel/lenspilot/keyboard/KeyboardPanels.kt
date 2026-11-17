package com.hemel.lenspilot.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hemel.lenspilot.Prefs
import com.hemel.lenspilot.R
import org.json.JSONArray

/** v127 — কপি-করা লেখার ইতিহাস (Gboard-এর ক্লিপবোর্ডের মতো)। শুধু এই ফোনে (Prefs-এ) থাকে; "Clear all" দিয়ে মোছা যায়। */
object ClipHistory {
    private const val MAX = 30

    fun items(ctx: Context): List<String> = try {
        val a = JSONArray(Prefs.clipHistoryJson(ctx))
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, list: List<String>) {
        Prefs.setClipHistoryJson(ctx, JSONArray(list).toString())
    }

    fun add(ctx: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty() || t.length > 3000) return
        val list = items(ctx).toMutableList()
        list.remove(t)
        list.add(0, t)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx, list)
    }

    fun remove(ctx: Context, text: String) {
        val list = items(ctx).toMutableList()
        list.remove(text)
        save(ctx, list)
    }

    fun clear(ctx: Context) {
        save(ctx, emptyList())
    }

    /** এখন ক্লিপবোর্ডে যা আছে (না থাকলে null)। */
    fun currentClipText(ctx: Context): String? = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}

/**
 * v127 — কীবোর্ডের ভেতরে বসা প্যানেল (কীগুলোর জায়গায়): ইমোজি, ক্লিপবোর্ড, টেক্সট-এডিট (কার্সার/সিলেক্ট/কাট/কপি/পেস্ট)।
 * প্রতিটা প্যানেলের নিচে [ABC] (কীতে ফেরা) আর [⌫]।
 */
class KeyboardPanels(
    private val ctx: Context,
    private val theme: () -> KeyboardTheme,
    private val commit: (String) -> Unit,
    private val backspace: () -> Unit,
    private val ic: () -> InputConnection?,
    private val onBack: () -> Unit
) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private fun key(label: String, textSp: Float, special: Boolean, onTap: () -> Unit): TextView {
        val t = theme()
        return TextView(ctx).apply {
            text = label
            textSize = textSp
            gravity = Gravity.CENTER
            setTextColor(t.text)
            background = if (special) t.specialBg(ctx) else t.keyBg(ctx)
            isClickable = true
            isFocusable = false
            maxLines = 1
            setOnClickListener { onTap() }
        }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** ভেতরের অংশ + নিচের বার (ABC / ⌫)। */
    private fun frame(content: View): LinearLayout {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(key(if (Prefs.keyboardBanglaMode(ctx)) "বাংলা" else "ABC", 16f, true) { onBack() },
            lp(0, dp(46), 1.4f).apply { marginEnd = dp(3) })
        bar.addView(View(ctx), lp(0, 1, 3f))
        val bs = key("⌫", 20f, true) { backspace() }
        attachRepeat(bs) { backspace() }
        bar.addView(bs, lp(0, dp(46), 1.4f).apply { marginStart = dp(3) })
        root.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        return root
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachRepeat(v: View, action: () -> Unit) {
        val run = object : Runnable {
            override fun run() { action(); ui.postDelayed(this, 45) }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> ui.postDelayed(run, 350)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(run)
            }
            false
        }
    }

    // ------------------------------------------------------------------
    // ইমোজি
    // ------------------------------------------------------------------

    private val emojiCats: List<Pair<String, List<String>>> = listOf(
        "😀" to ("😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖").split(" "),
        "👍" to ("👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐 🖖 👋 🤝 🙏 👏 🙌 👐 🤲 💪 🦾 ✍️ 🤳 💅 👀 👁 👅 👄 🧠 👂 👃 👣").split(" "),
        "❤️" to ("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉 ☸️ ✡️ 🔯 ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⭐ 🌟 ✨ ⚡ 🔥 💥 💫 💦 💤 ✅ ❌ ❓ ❗ ➕ ➖ ➗ ✔️ ☑️ 💯 🔔 🔕").split(" "),
        "🐶" to ("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🐘 🦏 🐪 🐫 🦒 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🕊 🐇 🐁 🐀 🌵 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃 🌸 🌹 🌺 🌻 🌼 🌷").split(" "),
        "🍔" to ("🍎 🍏 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥕 🌽 🌶 🥔 🍠 🍞 🥐 🥖 🧀 🥚 🍳 🥞 🥓 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🍤 🍙 🍚 🍘 🍥 🍢 🍡 🍧 🍨 🍦 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 ☕ 🍵 🥤 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🍾").split(" "),
        "⚽" to ("⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🏒 🏏 ⛳ 🏹 🎣 🥊 🥋 🎽 ⛸ 🎿 🏂 🏋️ 🤸 🏊 🚴 🏆 🥇 🥈 🥉 🏅 🎖 🎯 🎮 🎲 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 📚 ✏️ 📝").split(" "),
        "🚗" to ("🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛵 🏍 🚲 🛴 ✈️ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛳 🚂 🚆 🚇 🚊 🚉 🏠 🏡 🏢 🏥 🏦 🏨 🏫 ⛪ 🕌 🕍 ⛩ 🌍 🌎 🌏 🌋 🏔 ⛰ 🏝 🌅 🌄 🌇 🌃 🌉 ⌚ 📱 💻 ⌨️ 🖥 📷 📺 📻 ⏰ 🔋 🔌 💡 🔦 💰 💳 💎 🔧 🔨 🔑 🔒 🔓 📦 ✉️ 📌 📎").split(" ")
    )
    private var emojiCat = 0

    fun emojiView(): View {
        val t = theme()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(tabs) }
        val gridScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val tabViews = ArrayList<TextView>()

        fun fillGrid() {
            val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val list = emojiCats[emojiCat].second.filter { it.isNotBlank() }
            val perRow = 8
            var i = 0
            while (i < list.size) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (j in 0 until perRow) {
                    val e = list.getOrNull(i + j)
                    val cell = TextView(ctx).apply {
                        text = e ?: ""
                        textSize = 26f
                        gravity = Gravity.CENTER
                        if (e != null) {
                            isClickable = true
                            isFocusable = false
                            setOnClickListener { commit(e) }
                        }
                    }
                    row.addView(cell, lp(0, dp(48), 1f))
                }
                grid.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                i += perRow
            }
            gridScroll.removeAllViews()
            gridScroll.addView(grid)
            gridScroll.scrollTo(0, 0)
        }

        fun paintTabs() {
            tabViews.forEachIndexed { idx, v ->
                v.background = if (idx == emojiCat) t.accentBg(ctx, 12) else t.specialBg(ctx)
            }
        }

        emojiCats.forEachIndexed { idx, c ->
            val tv = key(c.first, 20f, true) { emojiCat = idx; paintTabs(); fillGrid() }
            tabViews.add(tv)
            tabs.addView(tv, lp(dp(46), dp(40)).apply { marginEnd = dp(4) })
        }
        paintTabs()
        fillGrid()
        col.addView(tabScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        col.addView(gridScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return frame(col)
    }

    // ------------------------------------------------------------------
    // ক্লিপবোর্ড
    // ------------------------------------------------------------------

    fun clipboardView(): View {
        val t = theme()
        // এখন ক্লিপবোর্ডে যা আছে তা ইতিহাসে না থাকলে যোগ করি (লিসেনার চালু হওয়ার আগে কপি হয়ে থাকতে পারে)
        ClipHistory.currentClipText(ctx)?.let { ClipHistory.add(ctx, it) }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.kb_menu_clipboard)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(t.text)
            setPadding(dp(8), 0, 0, 0)
        }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(body) }

        fun fill() {
            body.removeAllViews()
            val items = ClipHistory.items(ctx)
            if (items.isEmpty()) {
                body.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.kb_clip_empty)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(t.textMuted)
                    setPadding(dp(16), dp(28), dp(16), dp(28))
                }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                return
            }
            for (clip in items) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val tv = TextView(ctx).apply {
                    text = clip
                    textSize = 16f
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(t.text)
                    background = t.pillBg(ctx, 12)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    isFocusable = false
                    setOnClickListener { commit(clip) }
                }
                val x = key("✕", 16f, true) { ClipHistory.remove(ctx, clip); fill() }
                row.addView(tv, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(x, lp(dp(40), dp(40)).apply { marginStart = dp(4) })
                body.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            }
        }

        head.addView(key(ctx.getString(R.string.kb_clip_clear), 13f, true) { ClipHistory.clear(ctx); fill() },
            lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(4) })
        (head.getChildAt(1) as? TextView)?.setPadding(dp(12), 0, dp(12), 0)
        fill()
        col.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        col.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return frame(col)
    }

    // ------------------------------------------------------------------
    // টেক্সট এডিট — কার্সার, সিলেক্ট, সব-সিলেক্ট, কাট/কপি/পেস্ট
    // ------------------------------------------------------------------

    private class Act(val label: String, val sp: Float, val run: () -> Unit)

    private var selectOn = false

    private fun press(code: Int) {
        val c = ic() ?: return
        val now = SystemClock.uptimeMillis()
        val meta = if (selectOn) KeyEvent.META_SHIFT_ON else 0
        c.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        c.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun ctxAction(id: Int) {
        val c = ic() ?: return
        val ok = c.performContextMenuAction(id)
        if (!ok && id == android.R.id.paste) {
            ClipHistory.currentClipText(ctx)?.let { c.commitText(it, 1) }
        }
    }

    private fun delWord() {
        val c = ic() ?: return
        val before = c.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        c.deleteSurroundingText(before.length - i, 0)
    }

    fun editView(): View {
        val t = theme()
        selectOn = false
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var selBtn: TextView? = null
        fun paintSel() {
            selBtn?.background = if (selectOn) t.accentBg(ctx, 12) else t.specialBg(ctx)
            selBtn?.setTextColor(if (selectOn) t.onAccent else t.text)
        }
        val rows: List<List<Act>> = listOf(
            listOf(
                Act(ctx.getString(R.string.kb_edit_select_all), 14f, { ctxAction(android.R.id.selectAll) }),
                Act("↑", 22f, { press(KeyEvent.KEYCODE_DPAD_UP) }),
                Act(ctx.getString(R.string.kb_edit_select), 14f, { selectOn = !selectOn; paintSel() }),
                Act(ctx.getString(R.string.kb_edit_cut), 14f, { ctxAction(android.R.id.cut) })
            ),
            listOf(
                Act("←", 22f, { press(KeyEvent.KEYCODE_DPAD_LEFT) }),
                Act(ctx.getString(R.string.kb_edit_home), 14f, { press(KeyEvent.KEYCODE_MOVE_HOME) }),
                Act("→", 22f, { press(KeyEvent.KEYCODE_DPAD_RIGHT) }),
                Act(ctx.getString(R.string.kb_edit_copy), 14f, { ctxAction(android.R.id.copy) })
            ),
            listOf(
                Act(ctx.getString(R.string.kb_edit_del_word), 14f, { delWord() }),
                Act("↓", 22f, { press(KeyEvent.KEYCODE_DPAD_DOWN) }),
                Act(ctx.getString(R.string.kb_edit_end), 14f, { press(KeyEvent.KEYCODE_MOVE_END) }),
                Act(ctx.getString(R.string.kb_edit_paste), 14f, { ctxAction(android.R.id.paste) })
            )
        )
        for ((r, rowSpec) in rows.withIndex()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((c, spec) in rowSpec.withIndex()) {
                val k = key(spec.label, spec.sp, true) { spec.run() }
                if (r == 0 && c == 2) selBtn = k
                row.addView(k, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            col.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        paintSel()
        return frame(col)
    }

    fun toast(msg: Int) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
