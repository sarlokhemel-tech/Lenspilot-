package com.hemel.lenspilot.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/** ESP32-র Access Point-এর WiFi-তে কানেক্ট করে একটা [Network] ফেরত দেয় (Android 10+: সিস্টেম নিজেই "কানেক্ট
 * করবে?" ডায়ালগ দেখায়, WifiNetworkSpecifier দিয়ে — অ্যাপ বন্ধ করলে/release() করলে আগের WiFi-তে ফেরে)।
 * Android 9 বা ssid ফাঁকা হলে: ইউজার আগে থেকে যে WiFi-তে আছে সেটা ব্যবহার করে। কলব্যাক সবসময় মেইন থ্রেডে। */
class WifiConnector(ctx: Context) {

    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())

    fun connect(ssid: String, password: String, host: String, onDone: (Network?, String?) -> Unit) {
        fun done(n: Network?, err: String?) { main.post { onDone(n, err) } }
        if (ssid.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val n = findWifiNetworkFor(host)
            if (n != null) done(n, null)
            else done(null, "First connect to the ESP32 network in the phone's WiFi settings, then tap again.")
            return
        }
        connectSpecifier(ssid, password, ::done)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectSpecifier(ssid: String, password: String, done: (Network?, String?) -> Unit) {
        val b = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotEmpty()) b.setWpa2Passphrase(password)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(b.build())
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { done(network, null) }
            override fun onUnavailable() { done(null, "Could not connect to the ESP32 WiFi (cancelled or timed out).") }
        }
        callback = cb
        cm.requestNetwork(req, cb, 30_000)
    }

    @Suppress("DEPRECATION")
    private fun findWifiNetworkFor(host: String): Network? {
        val prefix = host.substringBeforeLast('.', "")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            if (lp.linkAddresses.any { it.address.hostAddress?.substringBeforeLast('.', "") == prefix }) return n
        }
        return null
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
