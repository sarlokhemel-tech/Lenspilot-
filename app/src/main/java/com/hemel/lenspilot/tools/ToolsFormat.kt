package com.hemel.lenspilot.tools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ফাইলের আকার ও তারিখ দেখানোর ছোট হেল্পার। */
internal object ToolsFormat {
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun date(ms: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
}
