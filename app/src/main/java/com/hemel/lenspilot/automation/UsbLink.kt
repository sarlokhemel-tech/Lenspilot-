package com.hemel.lenspilot.automation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume

/**
 * OTG তার দিয়ে ESP32 (CP210x / CH340 / FTDI / ESP32-S2·S3·C3-র নিজস্ব USB)। কন্ট্রোল-মোডে [SerialHttpTransport]-এর নিচে,
 * ফ্ল্যাশ-মোডে [EspFlasher]-এর নিচে একই লিংক। DTR/RTS আর বড-রেট এখান থেকে।
 */
class UsbLink private constructor(
    private val port: UsbSerialPort,
    val driver: UsbSerialDriver
) : SerialLink, SerialInputOutputManager.Listener {

    override val label = "OTG"
    override val rx = RxBuffer()
    @Volatile private var closed = false
    private var io: SerialInputOutputManager? = null

    /** ESP32-S2/S3/C3-র নিজস্ব USB (VID 0x303A): বড-রেট বদলানো অর্থহীন, রিসেট-সিকোয়েন্স আলাদা। */
    val isNativeUsb: Boolean get() = driver.device.vendorId == 0x303A

    private fun startIo() {
        val m = SerialInputOutputManager(port, this)
        io = m
        m.start()
    }

    override fun onNewData(data: ByteArray) {
        rx.put(data, 0, data.size)
    }

    override fun onRunError(e: Exception) {
        rx.fail(IOException(if (closed) "Connection closed" else "USB disconnected"))
    }

    override fun write(data: ByteArray) {
        if (closed) throw IOException("Connection closed")
        try {
            port.write(data, 5_000)
        } catch (e: IOException) {
            throw IOException("Could not write over USB: ${e.message}")
        }
    }

    fun setBaud(baud: Int) {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    /** DTR/RTS একসাথে বসানো (esptool-এর রিসেট-সিকোয়েন্স)। যে ড্রাইভার সাপোর্ট করে না সেখানে চুপচাপ বাদ। */
    fun setLines(dtr: Boolean, rts: Boolean) {
        runCatching { port.setDTR(dtr) }
        runCatching { port.setRTS(rts) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { io?.stop() }
        runCatching { port.close() }
    }

    companion object {
        private const val ACTION_PERMISSION = "com.hemel.lenspilot.USB_PERMISSION"

        private fun prober(): UsbSerialProber {
            val table = UsbSerialProber.getDefaultProbeTable()
            // ESP32-S2 / S3 / C3-র বিল্ট-ইন USB (CDC)
            table.addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
            table.addProduct(0x303A, 0x0002, CdcAcmSerialDriver::class.java)
            return UsbSerialProber(table)
        }

        fun findDriver(ctx: Context): UsbSerialDriver? {
            val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            return prober().findAllDrivers(mgr).firstOrNull()
        }

        suspend fun requestPermission(ctx: Context, device: UsbDevice): Boolean {
            val app = ctx.applicationContext
            val mgr = app.getSystemService(Context.USB_SERVICE) as UsbManager
            if (mgr.hasPermission(device)) return true
            return suspendCancellableCoroutine { cont ->
                val rcv = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.action != ACTION_PERMISSION) return
                        runCatching { app.unregisterReceiver(this) }
                        val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                ContextCompat.registerReceiver(
                    app, rcv, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(rcv) } }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(
                    app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags
                )
                mgr.requestPermission(device, pi)
            }
        }

        @Throws(IOException::class)
        fun open(ctx: Context, driver: UsbSerialDriver, baud: Int): UsbLink {
            val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val conn = mgr.openDevice(driver.device)
                ?: throw IOException("Could not open USB. Was permission granted?")
            val port = driver.ports.firstOrNull() ?: throw IOException("The USB device has no serial port")
            try {
                port.open(conn)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (e: Exception) {
                runCatching { port.close() }
                throw IOException("Could not open the USB port: ${e.message}")
            }
            val link = UsbLink(port, driver)
            link.setLines(false, false)
            link.startIo()
            return link
        }
    }
}
