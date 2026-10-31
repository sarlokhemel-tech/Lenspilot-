package com.hemel.lenspilot.tools.runner

/**
 * ব্রাউজার থেকে সেশনের workspace/downloads/-এ একটা ফাইল পুরোপুরি নামলে এই ঘটনা যায়।
 * file_appears detector-এর সাথে সামঞ্জস্যপূর্ণ: [relPath] সেশনের workspace-এর সাপেক্ষে (যেমন "downloads/report.pdf"),
 * ঠিক যে পাথে detector-এর glob মেলে। LLM-কে জাগায় না — শুধু কালেক্টর/লগের জন্য।
 */
class FileDownloaded(
    val sessionId: String,
    val relPath: String,
    val size: Long
) : ToolsEvent
