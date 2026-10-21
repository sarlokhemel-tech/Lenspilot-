package com.hemel.lenspilot.automation

import org.json.JSONObject
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * অ্যাপ-লক: ESP32-র কন্ট্রোল পেজ শুধু এই অ্যাপই খুলতে পারে, অন্য কেউ AP-র WiFi-তে ঢুকলেও না।
 *
 *   1. GET /lp/challenge → {"nonce":"<random>"}
 *   2. অ্যাপ বানায় mac = HMAC-SHA256(device_key, nonce) (hex) — device_key শুধু লগইন-করা অ্যাপ ইউজার
 *      ব্যাকএন্ড থেকে পায় (/api/automation/device-key/<id>)।
 *   3. POST /lp/auth {"mac":"..."} → {"token":"<random>"}
 *   4. এরপর প্রতিটা রিকোয়েস্টে `X-LP-Token`; ফার্মওয়্যার টোকেন ছাড়া /lp/… বাদে সব ফিরিয়ে দেয় (401)।
 * ESP32 একবারে একটাই রিকোয়েস্ট সামলায় — তাই সব রিকোয়েস্ট [io] লক দিয়ে সিরিয়াল করা।
 */
class DeviceSession(private val transport: DeviceTransport, private val deviceKey: String) {

    @Volatile private var token: String? = null
    private val io = Any()

    @Throws(IOException::class)
    fun ensureAuth(force: Boolean = false) {
        synchronized(io) {
            if (!force && token != null) return
            token = null
            val ch = transport.roundTrip(DeviceRequest("GET", "/lp/challenge", emptyMap(), ByteArray(0)))
            if (ch.status != 200) {
                throw IOException("ডিভাইস challenge দেয়নি (HTTP ${ch.status}) — এই ফার্মওয়্যারে অ্যাপ-লক আছে?")
            }
            val nonce = try {
                JSONObject(String(ch.body, Charsets.UTF_8)).getString("nonce")
            } catch (e: Exception) {
                throw IOException("ডিভাইসের challenge-উত্তর ভুল ফরম্যাটে")
            }
            val body = JSONObject().put("mac", hmacHex(deviceKey, nonce)).toString().toByteArray(Charsets.UTF_8)
            val au = transport.roundTrip(
                DeviceRequest("POST", "/lp/auth", mapOf("Content-Type" to "application/json"), body)
            )
            if (au.status != 200) {
                throw IOException("ডিভাইস অ্যাপকে চিনল না (HTTP ${au.status}) — ফার্মওয়্যারের ডিভাইস-কী আর অ্যাডমিন প্যানেলের কী মেলেনি")
            }
            token = try {
                JSONObject(String(au.body, Charsets.UTF_8)).getString("token")
            } catch (e: Exception) {
                throw IOException("ডিভাইসের auth-উত্তর ভুল ফরম্যাটে")
            }
        }
    }

    @Throws(IOException::class)
    fun forward(req: DeviceRequest): DeviceResponse {
        ensureAuth()
        synchronized(io) {
            var resp = transport.roundTrip(withToken(req))
            if (resp.status == 401) {
                // টোকেন বাতিল (ডিভাইস রিস্টার্ট/অন্য সেশন) — একবার নতুন করে লগইন
                token = null
            }
            if (token == null) {
                // io লক ইতিমধ্যে ধরা; ensureAuth রি-এন্ট্রান্ট (একই থ্রেড)
                ensureAuth(true)
                resp = transport.roundTrip(withToken(req))
            }
            return resp
        }
    }

    private fun withToken(req: DeviceRequest) = DeviceRequest(
        req.method, req.path, req.headers + ("X-LP-Token" to (token ?: "")), req.body
    )

    private fun hmacHex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
