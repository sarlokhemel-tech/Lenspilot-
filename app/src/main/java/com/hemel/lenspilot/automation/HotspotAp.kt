package com.hemel.lenspilot.automation

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * v37: ফোনেই অ্যাক্সেস পয়েন্ট (Local-Only Hotspot) তৈরি — root/সিস্টেম-পারমিশন ছাড়া অ্যান্ড্রয়েডের নিজস্ব API।
 * নাম/পাসওয়ার্ড অ্যান্ড্রয়েড নিজে বানায় (বদলানো যায় না), তাই ESP32-কে সেটা জানাতে হয় — দেখো AgentActivity-র
 * "অ্যাক্সেস পয়েন্ট বানিয়ে চালান" (ESP32-র নিজের AP দিয়ে /lp/provision-এ পাঠানো)। এই AP-তে ইন্টারনেট থাকে না;
 * ফোন নিজে মোবাইল ডেটা/অন্য WiFi-তেই থাকে, শুধু ESP32-র সাথে লোকাল যোগাযোগ।
 */
class HotspotAp(ctx: Context) {

    class Info(val ssid: String, val password: String)

    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val running: Boolean get() = reservation != null

    /** কলব্যাক মেইন থ্রেডে। পারমিশন (Android 13-এর নিচে লোকেশন, 13+ NEARBY_WIFI_DEVICES) আগে নিতে হবে। */
    @SuppressLint("MissingPermission")
    fun start(onDone: (Info?, String?) -> Unit) {
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val info = readInfo(res)
                    if (info == null) {
                        stop()
                        onDone(null, "অ্যাক্সেস পয়েন্ট চালু হয়েছে কিন্তু তার নাম/পাসওয়ার্ড পড়া যায়নি।")
                    } else {
                        onDone(info, null)
                    }
                }

                override fun onStopped() { reservation = null }

                override fun onFailed(reason: Int) {
                    reservation = null
                    onDone(null, reasonText(reason))
                }
            }, main)
        } catch (e: SecurityException) {
            onDone(null, "অ্যাক্সেস পয়েন্ট বানানোর পারমিশন নেই।")
        } catch (e: IllegalStateException) {
            onDone(null, "অ্যাক্সেস পয়েন্ট চালু করা যায়নি (${e.message ?: "ফোন রাজি নয়"})। ফোনের হটস্পট বন্ধ করে আবার চেষ্টা করুন।")
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }

    @Suppress("DEPRECATION")
    private fun readInfo(res: WifiManager.LocalOnlyHotspotReservation): Info? {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val c = res.softApConfiguration
                val ssid = c.ssid
                val pass = c.passphrase
                if (ssid.isNullOrBlank()) null else Info(ssid.trim('"'), pass.orEmpty())
            } else {
                val c = res.wifiConfiguration
                val ssid = c?.SSID
                if (ssid.isNullOrBlank()) null else Info(ssid.trim('"'), c?.preSharedKey.orEmpty().trim('"'))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "অ্যাক্সেস পয়েন্টের জন্য ফাঁকা চ্যানেল পাওয়া গেল না — ফোনের WiFi একবার বন্ধ-চালু করে আবার চেষ্টা করুন।"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "ফোনের হটস্পট/টেদারিং এখন চালু আছে — সেটা বন্ধ করে আবার চেষ্টা করুন।"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "এই ফোনে হটস্পট বানানো নিষিদ্ধ (ক্যারিয়ার/প্রশাসকের সেটিং)। তখন ESP32-র নিজের WiFi দিয়ে কানেক্ট করুন।"
        else -> "অ্যাক্সেস পয়েন্ট চালু হয়নি (কোড $reason)।"
    }
}

/**
 * ফোনের অ্যাক্সেস পয়েন্টে জোড়া-লাগা ESP32 খোঁজা: ফোনের নিজস্ব সাবনেটের (হটস্পটের /24) প্রতিটা ঠিকানায় দ্রুত
 * পোর্ট-৮০ দেখে, সাড়া দিলে `/lp/challenge` পড়ে নিশ্চিত হয় (শুধু অ্যাপ-লক-সমর্থিত ফার্মওয়্যারই "nonce" দেয়)।
 */
internal object ApScanner {

    private class Candidate(val prefix: String, val own: String, val apLike: Boolean)

    private fun candidates(): List<Candidate> {
        val out = ArrayList<Candidate>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in ifs) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
                    name.startsWith("ppp") || name.startsWith("v4-") || name.startsWith("dummy")
                ) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || !addr.isSiteLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    val apLike = name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") || name == "wlan1"
                    out.add(Candidate(ip.substringBeforeLast('.'), ip, apLike))
                }
            }
        } catch (e: Exception) {
            // খালি তালিকা — কলার আবার চেষ্টা করবে
        }
        return out.sortedByDescending { it.apLike }
    }

    private fun portOpen(ip: String): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress(ip, 80), 320); true }
    } catch (e: Exception) {
        false
    }

    private fun isLpDevice(ip: String): Boolean = try {
        val r = WifiTransport(null, ip).roundTrip(DeviceRequest("GET", "/lp/challenge", emptyMap(), ByteArray(0)))
        r.status == 200 && String(r.body, Charsets.UTF_8).contains("nonce")
    } catch (e: Exception) {
        false
    }

    /** [timeoutMs]-এর মধ্যে ESP32-র IP; না পেলে বা বাতিল হলে null। [onProgress] ঘুরে ঘুরে ডাকা হয় (IO থ্রেডে নয়, কলার সামলাবে)। */
    suspend fun findDevice(timeoutMs: Long, onRound: (Int) -> Unit): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var round = 0
        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            round++
            onRound(round)
            for (c in candidates()) {
                val hit = coroutineScope {
                    (2..254).map { n ->
                        async(Dispatchers.IO) {
                            val ip = "${c.prefix}.$n"
                            if (ip == c.own) null
                            else if (portOpen(ip) && isLpDevice(ip)) ip else null
                        }
                    }.awaitAll().firstOrNull { it != null }
                }
                if (hit != null) return@withContext hit
            }
            delay(1500)
        }
        null
    }
}
