package com.massliharf.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity(), WebOsClient.Listener {

    private lateinit var client: WebOsClient
    private lateinit var ir: IrRemote
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var btnConnect: Button
    private lateinit var btnIr: Button
    private lateinit var touchpad: TouchpadView
    private lateinit var dpadArea: View
    private lateinit var numpad: View

    private val main = Handler(Looper.getMainLooper())

    /** Keys that repeat while held down. */
    private val repeatKeys = setOf(
        "VOLUMEUP", "VOLUMEDOWN", "CHANNELUP", "CHANNELDOWN", "UP", "DOWN", "LEFT", "RIGHT",
    )

    /** Keys handled by dedicated SSAP endpoints rather than the pointer socket. */
    private val ssapKeys = mapOf(
        "VOLUMEUP" to "ssap://audio/volumeUp",
        "VOLUMEDOWN" to "ssap://audio/volumeDown",
        "CHANNELUP" to "ssap://tv/channelUp",
        "CHANNELDOWN" to "ssap://tv/channelDown",
        "PLAY" to "ssap://media.controls/play",
        "PAUSE" to "ssap://media.controls/pause",
        "STOP" to "ssap://media.controls/stop",
        "REWIND" to "ssap://media.controls/rewind",
        "FASTFORWARD" to "ssap://media.controls/fastForward",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        client = WebOsClient(this, this)
        ir = IrRemote(this)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        btnConnect = findViewById(R.id.btnConnect)
        btnIr = findViewById(R.id.btnIr)
        touchpad = findViewById(R.id.touchpad)
        dpadArea = findViewById(R.id.dpadArea)
        numpad = findViewById(R.id.numpad)

        wireKeys(findViewById(android.R.id.content))

        btnConnect.setOnClickListener { showConnectDialog() }
        btnIr.setOnClickListener { toggleIr() }
        findViewById<Button>(R.id.btnKeyboard).setOnClickListener { showKeyboardDialog() }
        findViewById<Button>(R.id.btnApps).setOnClickListener { showAppsDialog() }
        findViewById<Button>(R.id.btnNumbers).setOnClickListener {
            numpad.visibility = if (numpad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnPad).setOnClickListener {
            val showPad = touchpad.visibility != View.VISIBLE
            touchpad.visibility = if (showPad) View.VISIBLE else View.GONE
            dpadArea.visibility = if (showPad) View.GONE else View.VISIBLE
        }
        touchpad.listener = object : TouchpadView.Listener {
            override fun onMove(dx: Int, dy: Int) = client.move(dx, dy)
            override fun onTap() = client.click()
            override fun onScroll(dx: Int, dy: Int) = client.scroll(dx, dy)
        }

        updateIrButton()
        onStateChanged(client.state)
    }

    override fun onStart() {
        super.onStart()
        val last = Prefs.lastHost(this)
        if (last != null && client.state == WebOsClient.State.DISCONNECTED) client.connect(last)
    }

    override fun onStop() {
        super.onStop()
        client.disconnect()
    }

    // ---- Key handling ----

    @SuppressLint("ClickableViewAccessibility")
    private fun wireKeys(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) wireKeys(view.getChildAt(i))
            return
        }
        val key = view.tag as? String ?: return
        if (key in repeatKeys) {
            var repeater: Runnable? = null
            view.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        press(key, v)
                        repeater = object : Runnable {
                            override fun run() {
                                press(key, null)
                                main.postDelayed(this, 150)
                            }
                        }.also { main.postDelayed(it, 450) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        repeater?.let { main.removeCallbacks(it) }
                        repeater = null
                    }
                }
                false
            }
        } else {
            view.setOnClickListener { press(key, it) }
        }
    }

    private fun press(key: String, v: View?) {
        v?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        if (Prefs.irMode(this)) {
            sendIr(key)
            return
        }

        val connected = client.state == WebOsClient.State.CONNECTED
        if (key == "POWER") {
            when {
                connected -> client.request("ssap://system/turnOff")
                ir.available -> sendIr(key)
                else -> toast("TV'ye bağlı değil. Bu model (2014) Wi-Fi üzerinden açılamaz; " +
                    "açmak için TV'nin altındaki joystick tuşunu veya telefonun IR vericisini kullanın.")
            }
            return
        }

        if (!connected) {
            if (ir.available) sendIr(key) else toast("Önce TV'ye bağlanın (sağ üstteki \"Bağlan\")")
            return
        }

        when (key) {
            "INPUT" -> showInputsDialog()
            "MUTE" -> toggleMute()
            else -> {
                val uri = ssapKeys[key]
                if (uri != null) client.request(uri) else client.sendButton(key)
            }
        }
    }

    private fun sendIr(key: String) {
        val code = IrRemote.CODES[key]
        when {
            !ir.available -> toast("Bu telefonda kızılötesi (IR) verici yok")
            code == null -> toast("Bu tuş IR modunda desteklenmiyor")
            else -> ir.send(code)
        }
    }

    private fun toggleMute() {
        client.request("ssap://audio/getStatus") { payload, err ->
            if (err != null || payload == null || !payload.has("mute")) {
                client.sendButton("MUTE")
            } else {
                client.request("ssap://audio/setMute",
                    JSONObject().put("mute", !payload.optBoolean("mute")))
            }
        }
    }

    private fun toggleIr() {
        if (!ir.available) {
            toast("Bu telefonda kızılötesi (IR) verici yok. Wi-Fi modu kullanılıyor.")
            Prefs.setIrMode(this, false)
        } else {
            Prefs.setIrMode(this, !Prefs.irMode(this))
            toast(if (Prefs.irMode(this)) "IR modu açık: tuşlar kızılötesi ile gönderilir"
                  else "Wi-Fi modu: tuşlar ağ üzerinden gönderilir")
        }
        updateIrButton()
    }

    private fun updateIrButton() {
        val on = Prefs.irMode(this)
        btnIr.text = if (on) "IR ●" else "IR"
        btnIr.setTextColor(if (on) Color.parseColor("#FF3B82F6") else Color.parseColor("#FFECEFF4"))
    }

    // ---- Connection ----

    override fun onStateChanged(state: WebOsClient.State, message: String?) {
        val host = client.host ?: ""
        val (text, color) = when (state) {
            WebOsClient.State.DISCONNECTED ->
                (if (message != null) "Bağlantı yok: $message" else "Bağlı değil") to "#FF6B7280"
            WebOsClient.State.CONNECTING -> "Bağlanıyor… $host" to "#FFEAB308"
            WebOsClient.State.WAITING_FOR_PAIRING ->
                "TV ekranındaki isteği onaylayın (TV'nin altındaki joystick tuşuyla \"Evet\"i seçin)" to "#FFEAB308"
            WebOsClient.State.CONNECTED -> "Bağlandı: $host" to "#FF22C55E"
        }
        statusText.text = text
        statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
        btnConnect.text = if (state == WebOsClient.State.CONNECTED) "TV" else "Bağlan"
        if (state == WebOsClient.State.WAITING_FOR_PAIRING) {
            toast("TV'de eşleştirme isteği çıktı. Kabul edin.")
        }
    }

    @Suppress("DEPRECATION")
    private fun showConnectDialog() {
        val progress = ProgressDialog(this).apply {
            setMessage("Ağdaki LG TV aranıyor…")
            setCancelable(false)
            show()
        }
        Discovery.search(this) { tvs ->
            if (isFinishing) return@search
            progress.dismiss()
            val labels = tvs.map { "${it.name}\n${it.host}" } + "IP adresini elle gir…"
            AlertDialog.Builder(this)
                .setTitle(if (tvs.isEmpty()) "TV bulunamadı" else "TV seçin")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < tvs.size) client.connect(tvs[which].host) else showManualIpDialog()
                }
                .apply {
                    if (client.state != WebOsClient.State.DISCONNECTED) {
                        setNeutralButton("Bağlantıyı kes") { _, _ -> client.disconnect() }
                    }
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    private fun showManualIpDialog() {
        val input = EditText(this).apply {
            hint = "örn. 192.168.1.25"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(Prefs.lastHost(this@MainActivity) ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("TV IP adresi")
            .setMessage("TV'de: Ayarlar → Ağ → Ağ Bağlantısı / Durum ekranında görebilirsiniz.")
            .setView(padded(input))
            .setPositiveButton("Bağlan") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) client.connect(ip)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---- Dialogs that need a connection ----

    private fun requireConnected(): Boolean {
        if (client.state == WebOsClient.State.CONNECTED) return true
        toast("Önce TV'ye bağlanın")
        return false
    }

    private fun showKeyboardDialog() {
        if (!requireConnected()) return
        val input = EditText(this).apply { hint = "TV'ye gönderilecek metin" }
        AlertDialog.Builder(this)
            .setTitle("Klavye")
            .setMessage("TV'de bir arama/metin kutusu açıkken kullanın.")
            .setView(padded(input))
            .setPositiveButton("Gönder") { _, _ ->
                client.request("ssap://com.webos.service.ime/insertText",
                    JSONObject().put("text", input.text.toString()).put("replace", 0))
            }
            .setNeutralButton("Enter") { _, _ ->
                client.request("ssap://com.webos.service.ime/sendEnterKey")
            }
            .setNegativeButton("Sil ⌫") { _, _ ->
                client.request("ssap://com.webos.service.ime/deleteCharacters",
                    JSONObject().put("count", 1))
            }
            .show()
    }

    private fun showAppsDialog() {
        if (!requireConnected()) return
        client.request("ssap://com.webos.applicationManager/listLaunchPoints") { payload, err ->
            val list = payload?.optJSONArray("launchPoints")
            if (err == null && list != null && list.length() > 0) {
                showAppList(list, idKey = "id")
            } else {
                client.request("ssap://com.webos.applicationManager/listApps") { p2, e2 ->
                    val apps = p2?.optJSONArray("apps")
                    if (e2 == null && apps != null) showAppList(apps, idKey = "id")
                    else toast("Uygulama listesi alınamadı: ${e2 ?: err}")
                }
            }
        }
    }

    private fun showAppList(arr: org.json.JSONArray, idKey: String) {
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString(idKey)
            if (id.isEmpty() || o.optBoolean("hidden", false)) null
            else o.optString("title", id) to id
        }.sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Uygulamalar")
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                client.request("ssap://system.launcher/launch", JSONObject().put("id", items[which].second))
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showInputsDialog() {
        client.request("ssap://tv/getExternalInputList") { payload, err ->
            val devices = payload?.optJSONArray("devices")
            if (err != null || devices == null || devices.length() == 0) {
                // Fall back to the on-screen input picker.
                client.sendButton("INPUT")
                return@request
            }
            val items = (0 until devices.length()).mapNotNull { i ->
                val d = devices.optJSONObject(i) ?: return@mapNotNull null
                d.optString("label", d.optString("id")) to d.optString("id")
            }
            AlertDialog.Builder(this)
                .setTitle("Kaynak seçin")
                .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                    client.request("ssap://tv/switchInput", JSONObject().put("inputId", items[which].second))
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    // ---- Helpers ----

    private fun padded(v: View): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
