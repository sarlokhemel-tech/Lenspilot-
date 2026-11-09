package com.hemel.lenspilot.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hemel.lenspilot.R

/** v117: pins an "AI Browser" icon on the launcher's home screen. Tapping it opens the browser directly
 *  (manual mode, no task); an already-running browser is brought to the front instead of opening a second one.
 *  Used by the browser's ⋮ menu and by the side drawer button. */
object BrowserShortcut {
    fun requestPin(ctx: Context) {
        fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
        try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
                toast("Your launcher doesn't support adding shortcuts. Add it by long-pressing the home screen.")
                return
            }
            val launch = Intent(ctx, AiBrowserActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            val src = BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_lenspilot_logo)
            val icon = if (src != null)
                IconCompat.createWithBitmap(Bitmap.createScaledBitmap(src, 192, 192, true))
            else IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)
            val info = ShortcutInfoCompat.Builder(ctx, "lenspilot_ai_browser")
                .setShortLabel("AI Browser")
                .setLongLabel("Lenspilot AI Browser")
                .setIcon(icon)
                .setIntent(launch)
                .build()
            ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
            toast("Confirm on the pop-up to add it to your home screen")
        } catch (e: Exception) {
            toast("Couldn't add the shortcut: ${e.message ?: "unknown error"}")
        }
    }
}
