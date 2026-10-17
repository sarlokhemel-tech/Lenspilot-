package com.hemel.lenspilot.automation

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ব্লুটুথ (RFCOMM/SPP) আর OTG (USB সিরিয়াল) — দুটোই আসলে বাইট-স্ট্রিম। রিডার থ্রেড বাইট জমা করে [RxBuffer]-এ,
 * উপরের স্তর (SerialHttpTransport, EspFlasher) টাইমআউটসহ সেখান থেকে পড়ে।
 */
interface SerialLink {
    val label: String
    val rx: RxBuffer

    @Throws(IOException::class)
    fun write(data: ByteArray)

    fun close()
}

/** এক-কনজিউমার বাইট বাফার। রিডার থ্রেড [put]/[fail] ডাকে; পড়ে একটাই থ্রেড (ট্রান্সপোর্টের লক-ধরা কলার)। */
class RxBuffer {
    private val q = LinkedBlockingQueue<ByteArray>()
    private var cur: ByteArray? = null
    private var pos = 0

    @Volatile private var err: IOException? = null

    fun put(b: ByteArray, off: Int, len: Int) {
        if (len > 0) q.put(b.copyOfRange(off, off + len))
    }

    /** সংযোগ ভেঙে গেলে — পরের (এবং বাফারের বাকি বাইট শেষ হওয়ার পর) প্রতিটা read এই এরর ছুঁড়বে। */
    fun fail(e: IOException) {
        err = e
        q.put(EOF)
    }

    /** জমে থাকা পুরনো বাইট (বুট-লগ, আগের অর্ধেক উত্তর) ফেলে দেয়। */
    fun clear() {
        q.clear()
        cur = null
        pos = 0
    }

    /** ০..২৫৫ বাইট ফেরত দেয়; [timeoutMs] এর মধ্যে কিছু না এলে -১। সংযোগ ভাঙলে IOException। */
    @Throws(IOException::class)
    fun read(timeoutMs: Long): Int {
        val c = cur
        if (c != null && pos < c.size) return c[pos++].toInt() and 0xFF
        val next = q.poll(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        if (next == null) {
            err?.let { throw it }
            return -1
        }
        if (next === EOF) throw (err ?: IOException("সংযোগ বন্ধ"))
        cur = next
        pos = 1
        return next[0].toInt() and 0xFF
    }

    /** '\n' পর্যন্ত এক লাইন (ISO-8859-1)। [idleMs] ধরে একটা বাইটও না এলে null। বেশি লম্বা (আবর্জনা) লাইন ফেলে দিয়ে নতুন করে। */
    @Throws(IOException::class)
    fun readLine(idleMs: Long): String? {
        val sb = StringBuilder()
        while (true) {
            val b = read(idleMs)
            if (b < 0) return null
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 4096) sb.setLength(0)
        }
    }

    /** ঠিক [n] বাইট; মাঝে [idleMs] ধরে কিছু না এলে IOException। */
    @Throws(IOException::class)
    fun readN(n: Int, idleMs: Long): ByteArray {
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            val b = read(idleMs)
            if (b < 0) throw IOException("ডিভাইস উত্তরের মাঝপথে থেমে গেছে ($i/$n বাইট)")
            out[i++] = b.toByte()
        }
        return out
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
