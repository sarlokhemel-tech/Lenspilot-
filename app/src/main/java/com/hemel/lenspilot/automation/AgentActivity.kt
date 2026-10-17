package com.hemel.lenspilot.automation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

/**
 * একটা ডাউনলোড-করা এজেন্টের মেইন পেজ: মেশিন ESP32, কানেক্ট বাটন (ব্লুটুথ / WiFi / OTG — অ্যাডমিন যেগুলো
 * চালু রেখেছে), আর ফ্রেমওয়ার্ক ফ্ল্যাশ।
 *
 * তিন ধরনের কানেক্ট — সবগুলোর পরে একই পথ: অ্যাপ-লক (DeviceSession) → লোকাল প্রক্সি → অ্যাপের ভেতরের কন্ট্রোল পেজ:
 *   • WiFi: ESP32-র AP-তে কানেক্ট, HTTP সরাসরি।
 *   • ব্লুটুথ: ক্লাসিক SPP (RFCOMM) + সিরিয়াল-HTTP ব্রিজ (SerialHttpTransport)।
 *   • OTG: USB সিরিয়াল + একই ব্রিজ।
 * আর OTG দিয়ে ফ্রেমওয়ার্ক ফ্ল্যাশ (EspFlasher — esptool প্রোটোকল), অ্যাডমিনের দেওয়া পার্ট ও অফসেটে।
 */
class AgentActivity : AppCompatActivity() {

    companion object { const val EXTRA_ID = "agent_id" }

    private lateinit var agent: JSONObject
    private lateinit var agentId: String
    private lateinit var statusView: TextView
    private val connectButtons = mutableListOf<Button>()
    private var busy = false
    @Volatile private var activeFlasher: EspFlasher? = null
    private var permCallback: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val cb = permCallback
        permCallback = null
        cb?.invoke(res.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val found = if (id != null) AgentStore.find(this, id) else null
        if (found == null) { finish(); return }
        agent = found
        agentId = id!!
        val ctx = this

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AutoUi.BG)
            fitsSystemWindows = true
            setPadding(AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 8), AutoUi.dp(ctx, 16), AutoUi.dp(ctx, 8))
        }
        val header = AutoUi.row(ctx)
        header.addView(AutoUi.text(ctx, "←", 24f).apply {
            setPadding(0, 0, AutoUi.dp(ctx, 14), 0)
            setOnClickListener { finish() }
        })
        header.addView(AutoUi.text(ctx, agent.optString("title"), 18f, AutoUi.TEXT, true))
        root.addView(header)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val info = AutoUi.card(ctx)
        info.addView(AutoUi.text(ctx, "মেশিন: ESP32 (মাইক্রোকন্ট্রোলার)", 15f, AutoUi.TEXT, true))
        info.addView(AutoUi.text(ctx, "ফ্রেমওয়ার্ক v${agent.optInt("version", 1)}", 13f, AutoUi.MUTED).apply {
            setPadding(0, AutoUi.dp(ctx, 4), 0, 0)
        })
        statusView = AutoUi.text(ctx, "কানেক্ট করা নেই", 13f, AutoUi.ACCENT).apply {
            setPadding(0, AutoUi.dp(ctx, 8), 0, 0)
        }
        info.addView(statusView)
        body.addView(info)

        val conn = AutoUi.card(ctx)
        conn.addView(AutoUi.text(ctx, "কানেক্ট করুন", 14f, AutoUi.ACCENT, true))
        val transports = agent.optJSONArray("transports")
        val allowed = (0 until (transports?.length() ?: 0)).map { transports!!.getString(it) }.toSet()
        fun addConn(key: String, label: String, onClick: () -> Unit) {
            if (key !in allowed) return
            val b = AutoUi.button(ctx, label) { if (!busy) onClick() }
            connectButtons.add(b)
            conn.addView(AutoUi.gap(ctx, 8))
            conn.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addConn("bluetooth", "🔵  ব্লুটুথ কানেক্ট") { connectBluetooth() }
        addConn("wifi", "📶  WiFi কানেক্ট") { connectWifi() }
        addConn("otg", "🔌  OTG (USB) কানেক্ট") { connectOtg() }
        body.addView(conn)

        val fw = AutoUi.card(ctx)
        fw.addView(AutoUi.text(ctx, "ফ্রেমওয়ার্ক", 14f, AutoUi.ACCENT, true))
        val parts = agent.optJSONObject("flash")?.optJSONArray("parts")
        val n = parts?.length() ?: 0
        if (n == 0) {
            fw.addView(AutoUi.text(ctx, "অ্যাডমিন এখনো কোনো ফ্রেমওয়ার্ক ফাইল দেয়নি।", 13f, AutoUi.MUTED).apply {
                setPadding(0, AutoUi.dp(ctx, 6), 0, 0)
            })
        } else {
            for (i in 0 until n) {
                val p = parts!!.getJSONObject(i)
                val ok = AgentStore.partFile(ctx, agentId, i).exists()
                fw.addView(AutoUi.text(ctx,
                    "${if (ok) "✓" else "✗"}  ${p.optString("offset")}  •  ${p.optString("name")}", 13f, AutoUi.TEXT).apply {
                    setPadding(0, AutoUi.dp(ctx, 6), 0, 0)
                })
            }
            fw.addView(AutoUi.gap(ctx, 10))
            fw.addView(AutoUi.button(ctx, "⚡  ESP32-তে ফ্ল্যাশ করুন (OTG)", filled = false) { if (!busy) confirmFlash() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        body.addView(fw)

        root.addView(ScrollView(ctx).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun setBusy(b: Boolean, msg: String? = null) {
        busy = b
        connectButtons.forEach { it.alpha = if (b) 0.5f else 1f }
        if (msg != null) statusView.text = msg
    }

    private fun fail(msg: String) {
        setBusy(false, msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** ডিভাইস-কী (+ AP/ব্লুটুথ/বড তথ্য) আনে, তারপর [block] চালায়। কী না পেলে ব্যবহারকারীকে জানিয়ে থামে। */
    private fun withKey(block: suspend (JSONObject, String) -> Unit) {
        setBusy(true, "ডিভাইস-কী আনছি…")
        lifecycleScope.launch {
            val keyRes = AutomationApi.deviceKey(this@AgentActivity, agentId)
            val k = keyRes.getOrNull()
            if (k == null) { fail("কী আনা যায়নি: ${keyRes.exceptionOrNull()?.message}"); return@launch }
            val key = k.optString("device_key")
            if (key.isEmpty()) { fail("অ্যাডমিন এখনো ডিভাইস-কী বসায়নি"); return@launch }
            block(k, key)
        }
    }

    // ---------- WiFi ----------

    private fun connectWifi() {
        withKey { k, key ->
            val host = k.optString("ap_host").ifEmpty { "192.168.4.1" }
            statusView.text = "ESP32-র WiFi-তে কানেক্ট হচ্ছি…"
            val connector = WifiConnector(this@AgentActivity)
            connector.connect(k.optString("ap_ssid"), k.optString("ap_password"), host) { network, err ->
                if (network == null) {
                    connector.release()
                    fail(err ?: "কানেক্ট হয়নি")
                } else {
                    startSession(WifiTransport(network, host), key, connector, serial = false)
                }
            }
        }
    }

    // ---------- ব্লুটুথ ----------

    private fun ensureBtPermissions(cb: (Boolean) -> Unit) {
        val need = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { cb(true); return }
        permCallback = cb
        permLauncher.launch(missing.toTypedArray())
    }

    private fun connectBluetooth() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) { fail("এই ফোনে ব্লুটুথ নেই"); return }
        if (!adapter.isEnabled) { fail("আগে ফোনের ব্লুটুথ চালু করো"); return }
        setBusy(true, "ব্লুটুথ পারমিশন…")
        ensureBtPermissions { granted ->
            if (!granted) { fail("ব্লুটুথ পারমিশন ছাড়া কানেক্ট হবে না"); return@ensureBtPermissions }
            withKey { k, key ->
                val dev = pickBluetoothDevice(adapter, k.optString("bt_name"))
                if (dev == null) { fail("ব্লুটুথ ডিভাইস বাছা হয়নি"); return@withKey }
                statusView.text = "ব্লুটুথে কানেক্ট হচ্ছি…"
                val linkRes = withContext(Dispatchers.IO) { runCatching { BluetoothLink.connect(adapter, dev) } }
                val link = linkRes.getOrNull()
                if (link == null) { fail(linkRes.exceptionOrNull()?.message ?: "ব্লুটুথ কানেক্ট ব্যর্থ"); return@withKey }
                startSession(SerialHttpTransport(link), key, null, serial = true)
            }
        }
    }

    /** অ্যাডমিনের দেওয়া ব্লুটুথ-নামের সাথে মেলা পেয়ার-করা ডিভাইস থাকলে সেটাই; নইলে তালিকা + নতুন ডিভাইস খোঁজা। */
    @SuppressLint("MissingPermission")
    private suspend fun pickBluetoothDevice(adapter: BluetoothAdapter, wanted: String): BluetoothDevice? {
        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        if (wanted.isNotBlank()) {
            bonded.firstOrNull { it.name.equals(wanted, true) }?.let { return it }
        }
        return suspendCancellableCoroutine { cont ->
            val devices = ArrayList<BluetoothDevice>(bonded)
            val labels = ArrayList<String>()
            fun label(d: BluetoothDevice) = "${d.name ?: "(নামহীন)"}\n${d.address}"
            devices.forEach { labels.add(label(it)) }
            val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            var done = false
            var rcv: BroadcastReceiver? = null
            var dialog: AlertDialog? = null
            fun complete(d: BluetoothDevice?) {
                if (done) return
                done = true
                rcv?.let { runCatching { unregisterReceiver(it) } }
                runCatching { adapter.cancelDiscovery() }
                runCatching { dialog?.dismiss() }
                if (cont.isActive) cont.resume(d)
            }
            rcv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != BluetoothDevice.ACTION_FOUND) return
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
                    if (devices.any { it.address == d.address }) return
                    devices.add(d)
                    labels.add(label(d))
                    listAdapter.notifyDataSetChanged()
                    if (wanted.isNotBlank() && d.name.equals(wanted, true)) complete(d)
                }
            }
            ContextCompat.registerReceiver(
                this, rcv!!, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED
            )
            dialog = AlertDialog.Builder(this)
                .setTitle("ESP32-র ব্লুটুথ ডিভাইস বাছো")
                .setAdapter(listAdapter) { _, which -> complete(devices[which]) }
                .setNegativeButton("বাতিল") { _, _ -> complete(null) }
                .setOnCancelListener { complete(null) }
                .create()
            dialog?.show()
            adapter.startDiscovery()
            cont.invokeOnCancellation { complete(null) }
        }
    }

    // ---------- OTG ----------

    private fun connectOtg() {
        withKey { k, key ->
            val driver = UsbLink.findDriver(this@AgentActivity)
            if (driver == null) { fail("USB ডিভাইস পাওয়া যায়নি — OTG তার দিয়ে ESP32 ফোনে লাগাও"); return@withKey }
            statusView.text = "USB পারমিশন…"
            if (!UsbLink.requestPermission(this@AgentActivity, driver.device)) {
                fail("USB পারমিশন দেওয়া হয়নি"); return@withKey
            }
            val baud = k.optInt("serial_baud", 115200).let { if (it > 0) it else 115200 }
            val linkRes = withContext(Dispatchers.IO) { runCatching { UsbLink.open(this@AgentActivity, driver, baud) } }
            val link = linkRes.getOrNull()
            if (link == null) { fail(linkRes.exceptionOrNull()?.message ?: "USB কানেক্ট ব্যর্থ"); return@withKey }
            statusView.text = "ESP32 চালু হওয়ার জন্য অপেক্ষা…"
            delay(1500)          // পোর্ট খুললে অনেক বোর্ড রিসেট খায়
            link.rx.clear()
            startSession(SerialHttpTransport(link), key, null, serial = true)
        }
    }

    // ---------- সেশন (তিন ট্রান্সপোর্টেরই শেষ ধাপ) ----------

    private fun startSession(transport: DeviceTransport, key: String, connector: WifiConnector?, serial: Boolean) {
        statusView.text = "অ্যাপ-লক যাচাই হচ্ছে…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val session = DeviceSession(transport, key)
                    var attempt = 0
                    while (true) {
                        try { session.ensureAuth(); break } catch (e: IOException) {
                            attempt++
                            if (!serial || attempt >= 3) throw e
                            Thread.sleep(1200)     // সিরিয়ালে ESP32 তখনো বুট করছে হতে পারে
                        }
                    }
                    val proxy = LocalProxyServer(session)
                    val url = proxy.start()
                    DeviceHub.set(DeviceHub.Connection(agentId, transport, session, proxy, connector, url))
                }
            }
            if (res.isSuccess) {
                setBusy(false, "✓ ${transport.label}-এ কানেক্ট হয়েছে")
                startActivity(Intent(this@AgentActivity, DeviceConsoleActivity::class.java))
            } else {
                transport.close()
                connector?.release()
                fail(res.exceptionOrNull()?.message ?: "কানেক্ট ব্যর্থ")
            }
        }
    }

    // ---------- ফ্রেমওয়ার্ক ফ্ল্যাশ (OTG) ----------

    private fun confirmFlash() {
        AlertDialog.Builder(this)
            .setTitle("ESP32-তে ফ্ল্যাশ করবে?")
            .setMessage("ESP32 OTG তার দিয়ে ফোনে লাগানো থাকতে হবে। এতে ESP32-র আগের প্রোগ্রাম মুছে অ্যাডমিনের দেওয়া ফ্রেমওয়ার্ক বসবে। চলার সময় তার খুলো না।")
            .setPositiveButton("ফ্ল্যাশ করো") { _, _ -> runFlash() }
            .setNegativeButton("না", null)
            .show()
    }

    private fun runFlash() {
        setBusy(true, "USB খুঁজছি…")
        DeviceHub.disconnect()      // একই USB পোর্ট কন্ট্রোল-সেশন ধরে থাকলে ছাড়াও
        lifecycleScope.launch {
            val driver = UsbLink.findDriver(this@AgentActivity)
            if (driver == null) { fail("USB ডিভাইস পাওয়া যায়নি — OTG তার দিয়ে ESP32 ফোনে লাগাও"); return@launch }
            if (!UsbLink.requestPermission(this@AgentActivity, driver.device)) { fail("USB পারমিশন দেওয়া হয়নি"); return@launch }

            val partsRes = withContext(Dispatchers.IO) { runCatching { loadFlashParts() } }
            val parts = partsRes.getOrNull()
            if (parts == null) { fail(partsRes.exceptionOrNull()?.message ?: "ফ্ল্যাশ-ফাইল পড়া যায়নি"); return@launch }
            val baud = agent.optJSONObject("flash")?.optInt("baud", 460800) ?: 460800

            val msgView = AutoUi.text(this@AgentActivity, "শুরু হচ্ছে…", 14f, AutoUi.TEXT, true)
            val bar = ProgressBar(this@AgentActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            val logView = TextView(this@AgentActivity).apply { textSize = 11f; setPadding(0, AutoUi.dp(this@AgentActivity, 8), 0, 0) }
            val box = LinearLayout(this@AgentActivity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = AutoUi.dp(this@AgentActivity, 20)
                setPadding(pad, pad, pad, pad)
                addView(msgView); addView(bar); addView(logView)
            }
            val dlg = AlertDialog.Builder(this@AgentActivity)
                .setTitle("ফ্ল্যাশ চলছে — তার খুলো না")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("বাতিল", null)
                .create()
            dlg.show()
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                activeFlasher?.cancelled = true
                msgView.text = "বাতিল হচ্ছে…"
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val link = UsbLink.open(this@AgentActivity, driver, 115200)
                    try {
                        val fl = EspFlasher(link) { line -> runOnUiThread { logView.append(line + "\n") } }
                        activeFlasher = fl
                        fl.flashAll(parts, baud) { partNo, count, pct, msg ->
                            runOnUiThread {
                                bar.progress = ((partNo - 1) * 100 + pct) / count
                                msgView.text = msg
                            }
                        }
                    } finally {
                        activeFlasher = null
                        link.close()
                    }
                }
            }
            dlg.dismiss()
            if (result.isSuccess) {
                setBusy(false, "✓ ফ্ল্যাশ সম্পন্ন (${result.getOrNull()})")
                AlertDialog.Builder(this@AgentActivity)
                    .setTitle("ফ্ল্যাশ সম্পন্ন ✓")
                    .setMessage("ESP32-তে ফ্রেমওয়ার্ক বসে গেছে এবং যাচাই (MD5) মিলেছে। এখন OTG / WiFi / ব্লুটুথ কানেক্ট করে ব্যবহার করো।")
                    .setPositiveButton("ঠিক আছে", null)
                    .show()
            } else {
                fail(result.exceptionOrNull()?.message ?: "ফ্ল্যাশ ব্যর্থ")
            }
        }
    }

    private fun loadFlashParts(): List<EspFlasher.Part> {
        val parts = agent.optJSONObject("flash")?.optJSONArray("parts")
        val n = parts?.length() ?: 0
        if (n == 0) throw IOException("অ্যাডমিন এখনো কোনো ফ্রেমওয়ার্ক ফাইল দেয়নি")
        val list = ArrayList<EspFlasher.Part>()
        for (i in 0 until n) {
            val p = parts!!.getJSONObject(i)
            val f = AgentStore.partFile(this, agentId, i)
            if (!f.exists()) throw IOException("ফাইল ${i + 1} ফোনে নেই — এজেন্ট আবার ডাউনলোড করো")
            val off = try {
                p.optString("offset").trim().removePrefix("0x").removePrefix("0X").toLong(16)
            } catch (e: NumberFormatException) {
                throw IOException("ফাইল ${i + 1}-এর অফসেট ঠিক নেই (${p.optString("offset")})")
            }
            list.add(EspFlasher.Part(off, f.readBytes(), p.optString("name").ifEmpty { "part ${i + 1}" }))
        }
        return list
    }

    override fun onDestroy() {
        activeFlasher?.cancelled = true
        super.onDestroy()
    }
}
