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
import android.location.LocationManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.content.ClipData
import android.content.ClipboardManager
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
import kotlinx.coroutines.Job
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
    private var hotspot: HotspotAp? = null
    private var hotspotHandedOver = false
    private var apJob: Job? = null
    private var apDialog: AlertDialog? = null
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
        AutoUi.chrome(this)

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
        info.addView(AutoUi.text(ctx, "Machine: ESP32 (microcontroller)", 15f, AutoUi.TEXT, true))
        info.addView(AutoUi.text(ctx, "Framework v${agent.optInt("version", 1)}", 13f, AutoUi.MUTED).apply {
            setPadding(0, AutoUi.dp(ctx, 4), 0, 0)
        })
        statusView = AutoUi.text(ctx, "Not connected", 13f, AutoUi.ACCENT).apply {
            setPadding(0, AutoUi.dp(ctx, 8), 0, 0)
        }
        info.addView(statusView)
        body.addView(info)

        val conn = AutoUi.card(ctx)
        conn.addView(AutoUi.text(ctx, "Connect", 14f, AutoUi.ACCENT, true))
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
        addConn("bluetooth", "🔵  Bluetooth connect") { connectBluetooth() }
        addConn("wifi", "📶  WiFi connect (ESP32 AP)") { connectWifi() }
        addConn("wifi", "📡  Create access point and run") { connectViaPhoneAp() }
        addConn("otg", "🔌  OTG (USB) connect") { connectOtg() }
        body.addView(conn)

        val fw = AutoUi.card(ctx)
        fw.addView(AutoUi.text(ctx, "Framework", 14f, AutoUi.ACCENT, true))
        val parts = agent.optJSONObject("flash")?.optJSONArray("parts")
        val n = parts?.length() ?: 0
        if (n == 0) {
            fw.addView(AutoUi.text(ctx, "The admin has not provided a framework file yet.", 13f, AutoUi.MUTED).apply {
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
            fw.addView(AutoUi.button(ctx, "⚡  Flash the ESP32 (OTG)", filled = false) { if (!busy) confirmFlash() },
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
        setBusy(true, "Fetching device key…")
        lifecycleScope.launch {
            val keyRes = AutomationApi.deviceKey(this@AgentActivity, agentId)
            val k = keyRes.getOrNull()
            if (k == null) { fail("Could not fetch the key: ${keyRes.exceptionOrNull()?.message}"); return@launch }
            val key = k.optString("device_key")
            if (key.isEmpty()) { fail("The admin has not set the device key yet"); return@launch }
            block(k, key)
        }
    }

    // ---------- WiFi ----------

    private fun connectWifi() {
        withKey { k, key ->
            val host = k.optString("ap_host").ifEmpty { "192.168.4.1" }
            statusView.text = "Connecting to the ESP32 WiFi…"
            val connector = WifiConnector(this@AgentActivity)
            connector.connect(k.optString("ap_ssid"), k.optString("ap_password"), host) { network, err ->
                if (network == null) {
                    connector.release()
                    fail(err ?: "Not connected")
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
        if (adapter == null) { fail("This phone does not have Bluetooth"); return }
        if (!adapter.isEnabled) { fail("Turn on Bluetooth on the phone first"); return }
        setBusy(true, "Bluetooth permission…")
        ensureBtPermissions { granted ->
            if (!granted) { fail("Bluetooth permission is required to connect"); return@ensureBtPermissions }
            withKey { k, key ->
                val dev = pickBluetoothDevice(adapter, k.optString("bt_name"))
                if (dev == null) { fail("No Bluetooth device was selected"); return@withKey }
                statusView.text = "Connecting over Bluetooth…"
                val linkRes = withContext(Dispatchers.IO) { runCatching { BluetoothLink.connect(adapter, dev) } }
                val link = linkRes.getOrNull()
                if (link == null) { fail(linkRes.exceptionOrNull()?.message ?: "Bluetooth connection failed"); return@withKey }
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
            fun label(d: BluetoothDevice) = "${d.name ?: "(Unnamed)"}\n${d.address}"
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
                .setTitle("Select the ESP32 Bluetooth device")
                .setAdapter(listAdapter) { _, which -> complete(devices[which]) }
                .setNegativeButton("Cancel") { _, _ -> complete(null) }
                .setOnCancelListener { complete(null) }
                .create()
            dialog?.show()
            adapter.startDiscovery()
            cont.invokeOnCancellation { complete(null) }
        }
    }

    // ---------- ফোনে অ্যাক্সেস পয়েন্ট বানিয়ে চালানো (v37) ----------
    //
    // ১) ফোনের Local-Only Hotspot চালু (অ্যান্ড্রয়েড নিজে নাম/পাসওয়ার্ড দেয়)।
    // ২) ESP32-র নিজের AP-তে (অ্যাডমিনের দেওয়া SSID) ক্ষণিক জোড়া লেগে অ্যাপ-লক পেরিয়ে `/lp/provision`-এ ওই নাম/পাসওয়ার্ড দেওয়া —
    //    ESP32 সেটা সেভ করে ফোনের AP-তে ঢোকে। অ্যাডমিন SSID না দিলে বা ধাপটা ব্যর্থ হলে নাম/পাসওয়ার্ড দেখানো হয়
    //    (ইউজার/নিজস্ব ফার্মওয়্যার হাতে দিতে পারে), খোঁজা তবু চলতে থাকে।
    // ৩) ফোনের সাবনেটে ESP32 খোঁজা (`/lp/challenge`) → পাওয়া গেলে একই অ্যাপ-লক → কন্ট্রোল পেজ।

    private fun ensureApPermissions(cb: (Boolean) -> Unit) {
        val need = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { cb(true); return }
        permCallback = cb
        permLauncher.launch(missing.toTypedArray())
    }

    private fun locationIsOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(true)
    }

    private fun connectViaPhoneAp() {
        setBusy(true, "Checking permissions…")
        ensureApPermissions { granted ->
            if (!granted) {
                fail("Permission is required to create an access point")
            } else if (Build.VERSION.SDK_INT < 33 && !locationIsOn()) {
                fail("Turn on location (GPS). Android does not allow creating an access point without it.")
            } else {
                withKey { k, key -> startApFlow(k, key) }
            }
        }
    }

    private fun stopHotspot() {
        hotspot?.stop()
        hotspot = null
    }

    private fun startApFlow(k: JSONObject, key: String) {
        DeviceHub.disconnect()          // আগের সংযোগ থাকলে ছাড়ো (একই অ্যাক্সেস পয়েন্ট জোড়া ভাঙা এড়াতে)
        stopHotspot()
        hotspotHandedOver = false
        statusView.text = "Creating the phone access point…"
        val hs = HotspotAp(this)
        hotspot = hs
        hs.start { info, err ->
            if (info == null) {
                hotspot = null
                fail(err ?: "The access point did not start")
            } else {
                apJob?.cancel()
                apJob = lifecycleScope.launch { runApFlow(k, key, info) }
            }
        }
    }

    private class ApUi(val dialog: AlertDialog, val status: TextView)

    private fun showApDialog(info: HotspotAp.Info): ApUi {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = AutoUi.dp(ctx, 20)
            setPadding(pad, AutoUi.dp(ctx, 12), pad, AutoUi.dp(ctx, 4))
        }
        box.addView(AutoUi.text(ctx, "Phone access point is on ✓", 15f, AutoUi.INK, true))
        box.addView(AutoUi.text(ctx, "Network name (SSID)", 12f, AutoUi.MUTED).apply { setPadding(0, AutoUi.dp(ctx, 10), 0, 0) })
        box.addView(AutoUi.text(ctx, info.ssid, 16f, AutoUi.TEXT, true).apply { setTextIsSelectable(true) })
        box.addView(AutoUi.text(ctx, "Password", 12f, AutoUi.MUTED).apply { setPadding(0, AutoUi.dp(ctx, 8), 0, 0) })
        box.addView(AutoUi.text(ctx, info.password.ifEmpty { "(none)" }, 16f, AutoUi.TEXT, true).apply { setTextIsSelectable(true) })
        val st = AutoUi.text(ctx, "Starting…", 13f, AutoUi.ACCENT, true).apply {
            setPadding(0, AutoUi.dp(ctx, 14), 0, AutoUi.dp(ctx, 4))
        }
        box.addView(st)
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Access point")
            .setView(box)
            .setCancelable(false)
            .setNeutralButton("Copy password", null)
            .setNegativeButton("Close", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ap-password", info.password))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            apJob?.cancel()
            dlg.dismiss()
            stopHotspot()
            setBusy(false, "The access point was turned off")
        }
        apDialog = dlg
        return ApUi(dlg, st)
    }

    private suspend fun runApFlow(k: JSONObject, key: String, info: HotspotAp.Info) {
        val ui = showApDialog(info)
        val espSsid = k.optString("ap_ssid")
        val host = k.optString("ap_host").ifEmpty { "192.168.4.1" }
        var note = ""
        if (espSsid.isNotBlank()) {
            ui.status.text = "Sending access point details to the ESP32… (tap “Connect” if the phone asks)"
            val err = provisionViaEspAp(espSsid, k.optString("ap_password"), host, key, info)
            if (err != null) note = "Could not provision the ESP32 automatically ($err). Enter the name and password shown above on the ESP32 manually. "
        } else {
            note = "The admin has not set an AP name for the ESP32. Enter the name and password shown above on the ESP32 manually. "
        }
        ui.status.text = note + "Searching for ESP32…"
        val ip = ApScanner.findDevice(90_000L) { round ->
            runOnUiThread { ui.status.text = note + "Searching for ESP32… ($round)" }
        }
        if (ip == null) {
            ui.dialog.dismiss()
            stopHotspot()
            fail("The ESP32 was not found on the phone access point. Make sure it is powered on and has the correct name and password, then try again.")
            return
        }
        ui.dialog.dismiss()
        statusView.text = "ESP32 found ($ip)…"
        val hs = hotspot
        startSession(WifiTransport(null, ip), key, null, serial = false, onClose = { hs?.stop() })
    }

    /** ESP32-র নিজের AP-তে ক্ষণিক জোড়া লেগে ফোনের অ্যাক্সেস পয়েন্টের নাম/পাসওয়ার্ড তাকে দেয়। সফল হলে null, নইলে ত্রুটির কারণ। */
    private suspend fun provisionViaEspAp(
        ssid: String, pass: String, host: String, key: String, info: HotspotAp.Info
    ): String? {
        val connector = WifiConnector(this)
        val res = suspendCancellableCoroutine<Pair<Network?, String?>> { cont ->
            connector.connect(ssid, pass, host) { n, e -> if (cont.isActive) cont.resume(Pair(n, e)) }
            cont.invokeOnCancellation { connector.release() }
        }
        val net = res.first
        if (net == null) {
            connector.release()
            return res.second ?: "Could not connect to the ESP32 WiFi"
        }
        val err = withContext(Dispatchers.IO) {
            try {
                val session = DeviceSession(WifiTransport(net, host), key)
                session.ensureAuth()
                val body = JSONObject().put("ssid", info.ssid).put("pass", info.password)
                    .toString().toByteArray(Charsets.UTF_8)
                val resp = session.forward(
                    DeviceRequest("POST", "/lp/provision", mapOf("Content-Type" to "application/json"), body)
                )
                if (resp.status == 200) null
                else "The ESP32 did not accept the details (HTTP ${resp.status}). Does the firmware expose /lp/provision?"
            } catch (e: Exception) {
                e.message ?: "Could not send the details to the ESP32"
            }
        }
        connector.release()
        delay(1500)     // ESP32 ফোনের AP-তে ঢোকার সময় দাও
        return err
    }

    // ---------- OTG ----------

    private fun connectOtg() {
        withKey { k, key ->
            val driver = UsbLink.findDriver(this@AgentActivity)
            if (driver == null) { fail("No USB device found. Connect the ESP32 to the phone with an OTG cable."); return@withKey }
            statusView.text = "USB permission…"
            if (!UsbLink.requestPermission(this@AgentActivity, driver.device)) {
                fail("USB permission was not granted"); return@withKey
            }
            val baud = k.optInt("serial_baud", 115200).let { if (it > 0) it else 115200 }
            val linkRes = withContext(Dispatchers.IO) { runCatching { UsbLink.open(this@AgentActivity, driver, baud) } }
            val link = linkRes.getOrNull()
            if (link == null) { fail(linkRes.exceptionOrNull()?.message ?: "USB connection failed"); return@withKey }
            statusView.text = "Waiting for the ESP32 to start…"
            delay(1500)          // পোর্ট খুললে অনেক বোর্ড রিসেট খায়
            link.rx.clear()
            startSession(SerialHttpTransport(link), key, null, serial = true)
        }
    }

    // ---------- সেশন (তিন ট্রান্সপোর্টেরই শেষ ধাপ) ----------

    private fun startSession(
        transport: DeviceTransport, key: String, connector: WifiConnector?, serial: Boolean,
        onClose: (() -> Unit)? = null
    ) {
        statusView.text = "Verifying app lock…"
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
                    DeviceHub.set(DeviceHub.Connection(agentId, transport, session, proxy, connector, url, onClose))
                }
            }
            if (res.isSuccess) {
                if (onClose != null) hotspotHandedOver = true   // অ্যাক্সেস পয়েন্ট এখন DeviceHub বন্ধ করবে
                setBusy(false, "✓ Connected to ${transport.label}")
                startActivity(Intent(this@AgentActivity, DeviceConsoleActivity::class.java))
            } else {
                transport.close()
                connector?.release()
                onClose?.invoke()
                fail(res.exceptionOrNull()?.message ?: "Connection failed")
            }
        }
    }

    // ---------- ফ্রেমওয়ার্ক ফ্ল্যাশ (OTG) ----------

    private fun confirmFlash() {
        AlertDialog.Builder(this)
            .setTitle("Flash the ESP32?")
            .setMessage("The ESP32 must be connected to the phone with an OTG cable. This erases the ESP32's existing program and installs the framework provided by the admin. Do not unplug the cable while this runs.")
            .setPositiveButton("Flash") { _, _ -> runFlash() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun runFlash() {
        setBusy(true, "Searching for USB devices…")
        DeviceHub.disconnect()      // একই USB পোর্ট কন্ট্রোল-সেশন ধরে থাকলে ছাড়াও
        lifecycleScope.launch {
            val driver = UsbLink.findDriver(this@AgentActivity)
            if (driver == null) { fail("No USB device found. Connect the ESP32 to the phone with an OTG cable."); return@launch }
            if (!UsbLink.requestPermission(this@AgentActivity, driver.device)) { fail("USB permission was not granted"); return@launch }

            val partsRes = withContext(Dispatchers.IO) { runCatching { loadFlashParts() } }
            val parts = partsRes.getOrNull()
            if (parts == null) { fail(partsRes.exceptionOrNull()?.message ?: "Could not read the flash file"); return@launch }
            val baud = agent.optJSONObject("flash")?.optInt("baud", 460800) ?: 460800

            val msgView = AutoUi.text(this@AgentActivity, "Starting…", 14f, AutoUi.TEXT, true)
            val bar = ProgressBar(this@AgentActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            val logView = TextView(this@AgentActivity).apply { textSize = 11f; setPadding(0, AutoUi.dp(this@AgentActivity, 8), 0, 0) }
            val box = LinearLayout(this@AgentActivity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = AutoUi.dp(this@AgentActivity, 20)
                setPadding(pad, pad, pad, pad)
                addView(msgView); addView(bar); addView(logView)
            }
            val dlg = AlertDialog.Builder(this@AgentActivity)
                .setTitle("Flashing in progress. Do not unplug the cable.")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", null)
                .create()
            dlg.show()
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                activeFlasher?.cancelled = true
                msgView.text = "Cancelling…"
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
                setBusy(false, "✓ Flash complete (${result.getOrNull()})")
                AlertDialog.Builder(this@AgentActivity)
                    .setTitle("Flash complete ✓")
                    .setMessage("The framework is installed on the ESP32 and the checksum (MD5) matches. You can now connect over OTG, WiFi or Bluetooth.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                fail(result.exceptionOrNull()?.message ?: "Flash failed")
            }
        }
    }

    private fun loadFlashParts(): List<EspFlasher.Part> {
        val parts = agent.optJSONObject("flash")?.optJSONArray("parts")
        val n = parts?.length() ?: 0
        if (n == 0) throw IOException("The admin has not provided a framework file yet")
        val list = ArrayList<EspFlasher.Part>()
        for (i in 0 until n) {
            val p = parts!!.getJSONObject(i)
            val f = AgentStore.partFile(this, agentId, i)
            if (!f.exists()) throw IOException("File ${i + 1} is not on the phone. Download the agent again.")
            val off = try {
                p.optString("offset").trim().removePrefix("0x").removePrefix("0X").toLong(16)
            } catch (e: NumberFormatException) {
                throw IOException("File ${i + 1} has an invalid offset (${p.optString("offset")})")
            }
            list.add(EspFlasher.Part(off, f.readBytes(), p.optString("name").ifEmpty { "part ${i + 1}" }))
        }
        return list
    }

    override fun onDestroy() {
        activeFlasher?.cancelled = true
        apJob?.cancel()
        runCatching { apDialog?.dismiss() }
        if (!hotspotHandedOver) stopHotspot()   // কন্ট্রোল স্ক্রিনে না গেলে ফোনের অ্যাক্সেস পয়েন্ট ফেলে রাখা নয়
        super.onDestroy()
    }
}
