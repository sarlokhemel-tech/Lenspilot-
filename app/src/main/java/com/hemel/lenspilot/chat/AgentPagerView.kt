package com.hemel.lenspilot.chat

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.hemel.lenspilot.R
import com.hemel.lenspilot.multiagent.SourceViewerActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * v120 — মাল্টি-এজেন্ট সার্চের "লাইভ এজেন্ট কার্ড"।
 *
 * আগে সব এজেন্টের উত্তর + ক্রস-চেক শেষ না হওয়া পর্যন্ত স্ক্রিনে কিছুই আসত না, তাই সার্চ ধীর মনে হতো।
 * এখন সার্ভার প্রতিটা এজেন্ট শেষ হওয়া মাত্র `agent_answer` ইভেন্ট পাঠায়, আর এই ভিউ সেটা সঙ্গে সঙ্গে দেখায়:
 *
 *  - পেজ ১, ২, … = এজেন্ট A, B, … এর নিজস্ব উত্তর (যেটা আগে শেষ হয় সেটা আগে)।
 *  - এখনো শেষ হয়নি এমন এজেন্টের জায়গায় স্পিনারসহ "খুঁজছে…" পেজ — সোয়াইপ করলেই বোঝা যায় আরও আসছে।
 *  - সব এজেন্ট শেষ হলে শেষ পেজ = "গবেষণা": প্রতিটা এজেন্ট কী কী খুঁজেছে ও কোন কোন সাইট পড়েছে।
 *  - নিচে ‹ › চিহ্ন + ডট; হাত দিয়ে টেনেও (সোয়াইপ) পেজ বদলানো যায়।
 *
 * ভিউটার নিচে (ChatAdapter-এ) আগের মতোই মূল উত্তর, সোর্স-কার্ড, ছবি ও Resources বাটন আসে।
 *
 * ডেটা ফরম্যাট ([ChatMessage.agentCardsJson]):
 *   {"total":2,"agents":[{id,label,model,ok,answer,sources:[{title,url,site}],queries,search_mode,seconds,error}],
 *    "pending":{"B":"⏳ Agent B (…): searching…"}}
 */
class AgentPagerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private sealed class Page {
        abstract val sig: String
        data class Agent(val obj: JSONObject, val index: Int) : Page() {
            override val sig: String get() = "a:" + obj.optString("id") + ":" + obj.optString("answer").length + ":" + obj.optString("error")
        }
        data class Pending(val id: String, val status: String, val index: Int) : Page() {
            override val sig: String get() = "p:$id:$status"
        }
        data class Research(val agents: List<JSONObject>) : Page() {
            override val sig: String get() = "r:" + agents.joinToString(",") { it.optString("id") + it.optJSONArray("sources")?.length() }
        }
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val rv = RecyclerView(context)
    private val pageAdapter = PageAdapter()
    private val snap = PagerSnapHelper()
    private val prevBtn = TextView(context)
    private val nextBtn = TextView(context)
    private val titleView = TextView(context)
    private val dotsView = TextView(context)
    private var pages: List<Page> = emptyList()
    private var currentPage = 0

    init {
        orientation = VERTICAL
        rv.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rv.adapter = pageAdapter
        rv.overScrollMode = OVER_SCROLL_NEVER
        rv.itemAnimator = null
        rv.isNestedScrollingEnabled = false
        snap.attachToRecyclerView(rv)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) syncBar()
            }
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { syncBar() }
        })
        addView(rv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        styleArrow(prevBtn, "‹")
        styleArrow(nextBtn, "›")
        prevBtn.setOnClickListener { goTo(currentPage - 1) }
        nextBtn.setOnClickListener { goTo(currentPage + 1) }
        titleView.apply {
            textSize = 12.5f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary_light))
        }
        dotsView.apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFCBD5E1.toInt())
        }
        val mid = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(dotsView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        bar.addView(prevBtn, LayoutParams(dp(44), dp(40)))
        bar.addView(mid, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(nextBtn, LayoutParams(dp(44), dp(40)))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun styleArrow(t: TextView, glyph: String) {
        t.text = glyph
        t.gravity = Gravity.CENTER
        t.textSize = 26f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setTextColor(ContextCompat.getColor(context, R.color.primary_action))
        t.isClickable = true
        t.isFocusable = true
        t.contentDescription = if (glyph == "‹") "Previous agent" else "Next agent"
    }

    private fun goTo(i: Int) {
        if (i < 0 || i >= pages.size) return
        rv.smoothScrollToPosition(i)
    }

    /** ডেটা বসায়। একই পেজ আবার আঁকে না (তাই ইউজার যে পেজে আছে সেখানেই থাকে, ঝিলিক-ও নেই)। */
    fun bind(data: JSONObject) {
        val total = data.optInt("total", 0)
        val arr = data.optJSONArray("agents") ?: JSONArray()
        val arrived = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val list = ArrayList<Page>()
        arrived.forEachIndexed { i, o -> list.add(Page.Agent(o, i)) }
        val pending = data.optJSONObject("pending")
        val arrivedIds = arrived.map { it.optString("id") }.toSet()
        if (pending != null) {
            val ids = pending.keys().asSequence().filter { it !in arrivedIds }.sorted().toList()
            ids.forEachIndexed { i, id -> list.add(Page.Pending(id, pending.optString(id), arrived.size + i)) }
        }
        if (total > 0 && arrived.size >= total && arrived.isNotEmpty()) list.add(Page.Research(arrived))
        else if (total == 0 && arrived.isNotEmpty() && (pending == null || pending.length() == 0)) list.add(Page.Research(arrived))

        val old = pages
        pages = list
        val common = minOf(old.size, list.size)
        for (i in 0 until common) if (old[i].sig != list[i].sig) pageAdapter.notifyItemChanged(i)
        if (list.size > old.size) pageAdapter.notifyItemRangeInserted(old.size, list.size - old.size)
        else if (list.size < old.size) pageAdapter.notifyItemRangeRemoved(list.size, old.size - list.size)
        if (currentPage >= list.size) currentPage = maxOf(0, list.size - 1)
        syncBar()
    }

    private fun syncBar() {
        val lm = rv.layoutManager as LinearLayoutManager
        var pos = lm.findFirstCompletelyVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) pos = snap.findSnapView(lm)?.let { rv.getChildAdapterPosition(it) } ?: currentPage
        if (pos == RecyclerView.NO_POSITION) pos = 0
        currentPage = pos.coerceIn(0, maxOf(0, pages.size - 1))
        val n = pages.size
        prevBtn.alpha = if (currentPage > 0) 1f else 0.25f
        nextBtn.alpha = if (currentPage < n - 1) 1f else 0.25f
        titleView.text = when (val p = pages.getOrNull(currentPage)) {
            is Page.Agent -> "${p.obj.optString("label", "Agent ${p.obj.optString("id")}")}  ·  ${currentPage + 1}/$n"
            is Page.Pending -> "Agent ${p.id}  ·  ${currentPage + 1}/$n"
            is Page.Research -> "🔎 Research  ·  ${currentPage + 1}/$n"
            null -> ""
        }
        val sb = SpannableStringBuilder()
        for (i in 0 until n) {
            val start = sb.length
            sb.append(if (i == currentPage) "●" else "○")
            if (i < n - 1) sb.append(" ")
            if (i == currentPage) sb.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.primary_action)),
                start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        dotsView.text = sb
    }

    // ───────────────────────── পেজ-অ্যাডাপ্টার ─────────────────────────

    private inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = pages.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val root = FrameLayout(parent.context)
            root.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            root.setPadding(dp(2), dp(2), dp(2), dp(2))
            return PageVH(root)
        }
        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val root = holder.itemView as FrameLayout
            root.removeAllViews()
            root.addView(when (val p = pages[position]) {
                is Page.Agent -> agentCard(p)
                is Page.Pending -> pendingCard(p)
                is Page.Research -> researchCard(p)
            })
        }
    }

    private class PageVH(v: View) : RecyclerView.ViewHolder(v)

    private val accents = intArrayOf(0xFF2563EB.toInt(), 0xFF059669.toInt(), 0xFFEA580C.toInt(), 0xFF7C3AED.toInt(), 0xFFE11D48.toInt())
    private fun accent(i: Int) = accents[i.mod(accents.size)]

    private fun cardBox(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_source_card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minimumHeight = dp(190)
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun header(title: String, meta: String, color: Int, mark: String): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
        }, LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        row.addView(TextView(context).apply {
            text = title
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary_light))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (mark.isNotBlank()) row.addView(TextView(context).apply {
            text = mark
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary_light))
        })
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(row)
        if (meta.isNotBlank()) col.addView(TextView(context).apply {
            text = meta
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, R.color.text_muted_light))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2); marginStart = dp(18) })
        return col
    }

    private fun scrollBody(maxDp: Int, content: View): MaxHeightScrollView = MaxHeightScrollView(context).apply {
        maxHeightPx = dp(maxDp)
        isNestedScrollingEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun agentCard(p: Page.Agent): View {
        val o = p.obj
        val ok = o.optBoolean("ok", false)
        val secs = o.optDouble("seconds", 0.0)
        val metaParts = ArrayList<String>()
        o.optString("model").takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
        o.optString("search_mode").takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
        if (secs > 0) metaParts.add(String.format("%.0fs", secs))
        val box = cardBox()
        box.addView(header(o.optString("label", "Agent ${o.optString("id")}"), metaParts.joinToString(" · "), accent(p.index), if (ok) "✓" else "✗"))
        val answer = o.optString("answer", "").trim()
        val body = TextView(context).apply {
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary_light))
            setTextIsSelectable(false)
        }
        if (ok && answer.isNotBlank()) {
            body.text = runCatching {
                val dm = resources.displayMetrics
                com.hemel.lenspilot.learning.BoardMarkdown.render(answer, body.paint, dm.widthPixels - dp(72), dm.density, light = true) as CharSequence
            }.getOrDefault(answer)
        } else {
            body.text = o.optString("error", "").ifBlank { "No answer from this agent." }
            body.setTextColor(ContextCompat.getColor(context, R.color.text_secondary_light))
        }
        box.addView(scrollBody(230, body))
        return box
    }

    private fun pendingCard(p: Page.Pending): View {
        val box = cardBox()
        box.addView(header("Agent ${p.id}", "", accent(p.index), ""))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_secondary_light))
        }, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        row.addView(TextView(context).apply {
            text = p.status.ifBlank { "Searching…" }
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary_light))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        return box
    }

    private fun researchCard(p: Page.Research): View {
        val box = cardBox()
        box.addView(header("🔎 Research", "What each agent searched and read", 0xFF64748B.toInt(), ""))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        p.agents.forEachIndexed { i, o ->
            col.addView(TextView(context).apply {
                text = "${o.optString("label", "Agent ${o.optString("id")}")}" +
                    (o.optString("search_mode").takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "")
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent(i))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(12) })
            val qs = o.optJSONArray("queries")
            if (qs != null && qs.length() > 0) {
                col.addView(TextView(context).apply {
                    text = (0 until minOf(qs.length(), 6)).joinToString("\n") { "🔍 " + qs.optString(it) }
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary_light))
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            }
            val ss = o.optJSONArray("sources")
            if (ss != null && ss.length() > 0) {
                for (k in 0 until minOf(ss.length(), 8)) {
                    val s = ss.optJSONObject(k) ?: continue
                    val url = s.optString("url", "")
                    val site = s.optString("site", "")
                    val title = s.optString("title", "")
                    col.addView(TextView(context).apply {
                        text = "↗ " + (if (site.isNotBlank()) site else url) + (if (title.isNotBlank()) " — $title" else "")
                        textSize = 12.5f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(ContextCompat.getColor(context, R.color.primary_action))
                        if (url.startsWith("http")) {
                            setPadding(0, dp(4), 0, dp(4))
                            setOnClickListener {
                                runCatching {
                                    context.startActivity(
                                        Intent(context, SourceViewerActivity::class.java)
                                            .putExtra(SourceViewerActivity.EXTRA_URL, url)
                                            .putExtra(SourceViewerActivity.EXTRA_TITLE, title.ifBlank { site })
                                            .putExtra(SourceViewerActivity.EXTRA_SITE, site)
                                    )
                                }
                            }
                        }
                    }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
                }
            } else if (qs == null || qs.length() == 0) {
                col.addView(TextView(context).apply {
                    text = "No search details."
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(context, R.color.text_muted_light))
                })
            }
        }
        box.addView(scrollBody(230, col))
        return box
    }
}
