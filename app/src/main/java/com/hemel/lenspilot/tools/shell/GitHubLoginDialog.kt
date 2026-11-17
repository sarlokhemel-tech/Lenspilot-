package com.hemel.lenspilot.tools.shell

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hemel.lenspilot.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * GitHub লগইন ডায়ালগ (Device Flow): ইউজার-কোড দেখায়, বাটনে ভেরিফিকেশন URL খোলে,
 * সার্ভারের interval ধরে পোল করে। টোকেন এখানে কখনো আসে না।
 */
object GitHubLoginDialog {

    private var showing: AlertDialog? = null

    fun show(activity: AppCompatActivity, onDone: (Boolean) -> Unit = {}) {
        if (activity.isFinishing || showing?.isShowing == true) return
        val ctx: Context = activity
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val info = TextView(ctx).apply { textSize = 14f }
        val code = TextView(ctx).apply {
            textSize = 30f
            typeface = Typeface.MONOSPACE
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        val open = Button(ctx).apply {
            text = ctx.getString(R.string.shell_gh_open)
            visibility = View.GONE
        }
        val status = TextView(ctx).apply {
            textSize = 13f
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(info)
        box.addView(code)
        box.addView(open)
        box.addView(status)

        var job: Job? = null
        val builder = AlertDialog.Builder(ctx)
            .setTitle(R.string.shell_gh_title)
            .setView(box)

        if (GitHubAuth.isLoggedIn(ctx)) {
            info.setText(R.string.shell_gh_logged_in)
            builder.setNegativeButton(R.string.shell_gh_logout) { _, _ ->
                GitHubAuth.logout(ctx)
                Toast.makeText(ctx, R.string.shell_gh_logged_out, Toast.LENGTH_SHORT).show()
                onDone(false)
            }
            builder.setPositiveButton(R.string.shell_gh_close, null)
            val d = builder.create()
            showing = d
            d.setOnDismissListener { showing = null }
            d.show()
            return
        }

        builder.setNegativeButton(R.string.shell_gh_cancel, null)
        val dialog = builder.create()
        showing = dialog
        dialog.setOnDismissListener {
            job?.cancel()
            showing = null
        }
        dialog.show()

        info.setText(R.string.shell_gh_starting)
        job = activity.lifecycleScope.launch {
            try {
                val dc = GitHubAuth.requestDeviceCode(ctx)
                info.setText(R.string.shell_gh_instructions)
                code.text = dc.userCode
                code.visibility = View.VISIBLE
                open.visibility = View.VISIBLE
                status.setText(R.string.shell_gh_waiting)
                open.setOnClickListener {
                    try {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("github-code", dc.userCode))
                        Toast.makeText(ctx, R.string.shell_gh_code_copied, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                    }
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
                    } catch (e: ActivityNotFoundException) {
                        status.text = dc.verificationUri
                    }
                }
                when (val r = GitHubAuth.pollForToken(ctx, dc)) {
                    is GitHubAuth.PollResult.Success -> {
                        Toast.makeText(ctx, R.string.shell_gh_ok, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onDone(true)
                    }
                    is GitHubAuth.PollResult.Expired -> finish(code, open, status, ctx.getString(R.string.shell_gh_expired))
                    is GitHubAuth.PollResult.Denied -> finish(code, open, status, ctx.getString(R.string.shell_gh_denied))
                    is GitHubAuth.PollResult.Failed -> finish(code, open, status, r.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(code, open, status, e.message ?: ctx.getString(R.string.shell_gh_network))
            }
        }
    }

    private fun finish(code: TextView, open: Button, status: TextView, msg: String) {
        code.visibility = View.GONE
        open.visibility = View.GONE
        status.text = msg
    }
}
