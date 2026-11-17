package com.hemel.lenspilot.keyboard

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import com.hemel.lenspilot.Prefs

/**
 * v122 — কীবোর্ডের থিম। মেইন থিম "lenspilot" = অ্যাপের চ্যাট বক্সের মতো (বেইজ পাতা, সাদা গোল কী, নীল অ্যাকসেন্ট)।
 * "glass" = বাবলের গ্লাস-কার্ড চেহারা (ঘষা-কাচ কী, সাদা বোল্ড লেখা)। বাকিগুলো রঙের বিকল্প।
 * সব ড্রয়েবল কোডে বানানো — তাই থিম বদলালে XML লাগে না, সঙ্গে সঙ্গে বদলায়।
 */
class KeyboardTheme(
    val id: String,
    val label: String,
    val bg: Int,
    val bgEnd: Int,
    val key: Int,
    val keyPressed: Int,
    val keyStroke: Int,
    val special: Int,
    val specialPressed: Int,
    val text: Int,
    val textMuted: Int,
    val accent: Int,
    val onAccent: Int,
    val glass: Boolean,
    val radiusDp: Int
) {
    private fun d(ctx: Context) = ctx.resources.displayMetrics.density

    private fun shape(ctx: Context, fill: Int, stroke: Int, radiusDp: Int, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusDp * d(ctx)
            if (stroke != 0) setStroke(maxOf(1, (strokeDp * d(ctx)).toInt()), stroke)
        }

    /** গ্লাস কার্ড: ওপরে-নিচে হালকা গ্রেডিয়েন্ট + ওপরে নরম আলোর রেখা + সরু সাদা বর্ডার (bg_live_glass_card-এর মতো)। */
    private fun glassCard(ctx: Context, top: Int, bottom: Int, radiusDp: Int): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d(ctx)
            setStroke(maxOf(1, d(ctx).toInt()), 0x59FFFFFF)
        }
        val shine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33FFFFFF, 0x00FFFFFF)).apply {
            shape = GradientDrawable.RECTANGLE
            val r = (radiusDp - 1) * d(ctx)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        return LayerDrawable(arrayOf(base, shine)).apply {
            val px = maxOf(1, d(ctx).toInt())
            setLayerInset(1, px, px, px, (radiusDp * d(ctx) * 0.9f).toInt())
        }
    }

    fun rootBg(ctx: Context): Drawable =
        if (bgEnd != bg) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bg, bgEnd))
        else GradientDrawable().apply { setColor(bg) }

    private fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    fun keyBg(ctx: Context): Drawable =
        if (glass) pressable(glassCard(ctx, 0x40FFFFFF, 0x1FFFFFFF, radiusDp), glassCard(ctx, 0x73FFFFFF, 0x4DFFFFFF, radiusDp))
        else pressable(shape(ctx, key, keyStroke, radiusDp), shape(ctx, keyPressed, keyStroke, radiusDp))

    fun specialBg(ctx: Context): Drawable =
        if (glass) pressable(glassCard(ctx, 0x2EFFFFFF, 0x14FFFFFF, radiusDp), glassCard(ctx, 0x66FFFFFF, 0x40FFFFFF, radiusDp))
        else pressable(shape(ctx, special, keyStroke, radiusDp), shape(ctx, specialPressed, keyStroke, radiusDp))

    /** চালু অবস্থার (নীল) পিল/বাটন। */
    fun accentBg(ctx: Context, radius: Int = 16): Drawable =
        if (glass) glassCard(ctx, (accent and 0x00FFFFFF) or (0xCC shl 24), (accent and 0x00FFFFFF) or (0x99 shl 24), radius)
        else shape(ctx, accent, 0, radius)

    /** সাজেশন/লেখার-ঘরের গোল পিল — মেইন চ্যাটের কম্পোজারের মতো। */
    fun pillBg(ctx: Context, radius: Int = 18): Drawable =
        if (glass) glassCard(ctx, 0x38FFFFFF, 0x1CFFFFFF, radius) else shape(ctx, key, keyStroke, radius)

    /** চ্যাট-প্যানেলে আমার বাবল (ডানে, নিচে-ডানে ছোট কোণ)। */
    fun bubbleUser(ctx: Context): Drawable {
        val r = 16 * d(ctx); val s = 5 * d(ctx)
        return if (glass) glassCard(ctx, 0xCC2563EB.toInt(), 0xA61D4ED8.toInt(), 16)
        else GradientDrawable().apply { setColor(accent); cornerRadii = floatArrayOf(r, r, r, r, s, s, r, r) }
    }

    /** AI-র বাবল (বাঁয়ে, নিচে-বাঁয়ে ছোট কোণ)। */
    fun bubbleAi(ctx: Context): Drawable {
        val r = 16 * d(ctx); val s = 5 * d(ctx)
        return if (glass) glassCard(ctx, 0xB36B7587.toInt(), 0xAD586172.toInt(), 16)
        else GradientDrawable().apply {
            setColor(special); setStroke(maxOf(1, d(ctx).toInt()), keyStroke)
            cornerRadii = floatArrayOf(r, r, r, r, r, r, s, s)
        }
    }

    val userBubbleText: Int get() = 0xFFFFFFFF.toInt()
    val aiBubbleText: Int get() = if (glass) 0xFFFFFFFF.toInt() else text

    companion object {
        private fun c(s: String) = android.graphics.Color.parseColor(s)

        val ALL: List<KeyboardTheme> = listOf(
            // মেইন: অ্যাপের চ্যাট বক্সের মতো — বেইজ পাতা (#FAF9F5), সাদা কী, হালকা বর্ডার, নীল সেন্ড
            KeyboardTheme("lenspilot", "Lenspilot (like chat box)", c("#FAF9F5"), c("#FAF9F5"),
                c("#FFFFFF"), c("#EDEAE0"), c("#E2E8F0"), c("#EFEBE0"), c("#E4DED1"),
                c("#1F1E1D"), c("#6B6862"), c("#2563EB"), c("#FFFFFF"), false, 12),
            // গ্লাস কার্ড — বাবলের গ্লাস চ্যাটের মতো ঘষা-কাচ কী
            KeyboardTheme("glass", "Glass card", c("#4A5668"), c("#2B3340"),
                0, 0, 0, 0, 0,
                c("#FFFFFF"), c("#D5DCE6"), c("#2563EB"), c("#FFFFFF"), true, 12),
            KeyboardTheme("dark", "Dark", c("#15181E"), c("#15181E"),
                c("#2A3039"), c("#3A424E"), c("#2A3039"), c("#202530"), c("#343C48"),
                c("#F1F5F9"), c("#94A3B8"), c("#3B82F6"), c("#FFFFFF"), false, 10),
            KeyboardTheme("ocean", "Ocean blue", c("#EAF1FF"), c("#EAF1FF"),
                c("#FFFFFF"), c("#D6E4FF"), c("#D3E0F8"), c("#DCE8FF"), c("#C6D8FA"),
                c("#0B1B4A"), c("#5B6B94"), c("#1E6BFF"), c("#FFFFFF"), false, 12),
            KeyboardTheme("mint", "Mint green", c("#EAF7F0"), c("#EAF7F0"),
                c("#FFFFFF"), c("#D2EFE0"), c("#CFE8DA"), c("#D8F0E4"), c("#C2E6D3"),
                c("#0F3D2B"), c("#4F7A68"), c("#059669"), c("#FFFFFF"), false, 12),
            KeyboardTheme("rose", "Rose", c("#FFF1F3"), c("#FFF1F3"),
                c("#FFFFFF"), c("#FFD9DF"), c("#F6D3D9"), c("#FFE3E8"), c("#FBCBD3"),
                c("#4A1420"), c("#8F5A66"), c("#E11D48"), c("#FFFFFF"), false, 12)
        )

        fun byId(id: String?): KeyboardTheme = ALL.firstOrNull { it.id == id } ?: ALL[0]

        fun current(ctx: Context): KeyboardTheme = byId(Prefs.keyboardTheme(ctx))
    }
}
