package com.hemel.lenspilot.automation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * ESP32-র ক্লাসিক ব্লুটুথ (BluetoothSerial / SPP) এর সাথে RFCOMM সংযোগ। ESP32-S3/C3/C6 শুধু BLE জানে —
 * ওগুলোর জন্য OTG বা WiFi ব্যবহার করতে হবে (দেখো AUTOMATION_FRAMEWORK.md)।
 * সবকিছু ব্লকিং — ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে। BLUETOOTH_CONNECT (Android 12+) পারমিশন কলারের দায়িত্ব।
 */
@SuppressLint("MissingPermission")
class BluetoothLink private constructor(
    private val socket: BluetoothSocket,
    deviceName: String
) : SerialLink {

    override val label = "ব্লুটুথ"
    override val rx = RxBuffer()
    private val out: OutputStream = socket.outputStream
    @Volatile private var closed = false

    init {
        val t = Thread {
            val buf = ByteArray(2048)
            try {
                val inp = socket.inputStream
                while (!closed) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    rx.put(buf, 0, n)
                }
                rx.fail(IOException(if (closed) "সংযোগ বন্ধ" else "ব্লুটুথ সংযোগ বন্ধ হয়েছে"))
            } catch (e: IOException) {
                rx.fail(IOException(if (closed) "সংযোগ বন্ধ" else "ব্লুটুথ সংযোগ ভেঙে গেছে"))
            }
        }
        t.isDaemon = true
        t.name = "lp-bt-rx-$deviceName"
        t.start()
    }

    override fun write(data: ByteArray) {
        if (closed) throw IOException("সংযোগ বন্ধ")
        try {
            // ছোট ছোট টুকরোয় — কিছু ESP32 BluetoothSerial বড় লেখায় বাফার উপচে ফেলে
            var i = 0
            while (i < data.size) {
                val n = minOf(512, data.size - i)
                out.write(data, i, n)
                out.flush()
                i += n
            }
        } catch (e: IOException) {
            throw IOException("ব্লুটুথে লেখা যায়নি: ${e.message}")
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** আগে সিকিউর, না হলে ইনসিকিওর RFCOMM — যেটাই লাগে। */
        @Throws(IOException::class)
        fun connect(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothLink {
            runCatching { adapter.cancelDiscovery() }
            var last: IOException? = null
            for (secure in booleanArrayOf(true, false)) {
                val s = try {
                    if (secure) device.createRfcommSocketToServiceRecord(SPP_UUID)
                    else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: IOException) {
                    last = e
                    continue
                }
                try {
                    s.connect()
                    return BluetoothLink(s, device.name ?: device.address)
                } catch (e: IOException) {
                    last = e
                    runCatching { s.close() }
                }
            }
            throw IOException(
                "ব্লুটুথে কানেক্ট হয়নি (${last?.message ?: "অজানা"}) — ESP32 চালু আছে? ফোনের ব্লুটুথ সেটিংসে একবার পেয়ার করেছ?"
            )
        }
    }
}
