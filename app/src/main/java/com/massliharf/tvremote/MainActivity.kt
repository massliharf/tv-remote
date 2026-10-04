package com.massliharf.tvremote

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity(), WebOsClient.Listener {

    private lateinit var client: WebOsClient
    private lateinit var ir: IrRemote
    private lateinit var statusChip: KeyButton
    private lateinit var btnPad: KeyButton
    private lateinit var dpad: DpadView
    private lateinit var touchpad: TouchpadView
    private lateinit var modeHint: TextView

    private var pairingSheet: Sheet? = null
    private var connectSheet: Sheet? = null

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
        val root = findViewById<View>(android.R.id.content)
        Fonts.apply(root)

        client = WebOsClient(this, this)
        ir = IrRemote(this)

        statusChip = findViewById(R.id.statusChip)
        btnPad = findViewById(R.id.btnPad)
        dpad = findViewById(R.id.dpad)
        touchpad = findViewById(R.id.touchpad)
        modeHint = findViewById(R.id.modeHint)

        wireKeys(root)
        dpad.onKey = { press(it) }
        findViewById<RockerView>(R.id.volRocker).onKey = { press(it) }
        findViewById<RockerView>(R.id.chRocker).onKey = { press(it) }

        statusChip.setOnClickListener { showConnectSheet() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showAdvancedSheet() }
        findViewById<View>(R.id.btnKeyboard).setOnClickListener { showKeyboardSheet() }
        findViewById<View>(R.id.btnApps).setOnClickListener { showAppsSheet() }
        btnPad.setOnClickListener { setTouchpad(touchpad.visibility != View.VISIBLE) }
        touchpad.listener = object : TouchpadView.Listener {
            override fun onMove(dx: Int, dy: Int) = client.move(dx, dy)
            override fun onTap() = client.click()
            override fun onScroll(dx: Int, dy: Int) = client.scroll(dx, dy)
        }

        updateModeHint()
        onStateChanged(client.state)
    }

    override fun onStart() {
        super.onStart()
        val last = Prefs.lastHost(this)
        if (last == null) {
            showConnectSheet()
        } else if (client.state == WebOsClient.State.DISCONNECTED) {
            client.connect(last)
        }
    }

    override fun onStop() {
        super.onStop()
        client.disconnect()
    }

    private fun setTouchpad(on: Boolean) {
        touchpad.visibility = if (on) View.VISIBLE else View.GONE
        dpad.visibility = if (on) View.GONE else View.VISIBLE
        btnPad.text = if (on) "Yön tuşu" else "Touchpad"
        btnPad.icon = getDrawable(if (on) R.drawable.ic_dpad else R.drawable.ic_touch)
    }

    // ---- Key handling ----

    /** Every KeyButton with a String tag sends that key when tapped. */
    private fun wireKeys(view: View, after: (() -> Unit)? = null) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) wireKeys(view.getChildAt(i), after)
            return
        }
        val key = view.tag as? String ?: return
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            press(key)
            after?.invoke()
        }
    }

    private fun press(key: String) {
        if (Prefs.irMode(this)) {
            sendIr(key)
            return
        }

        val connected = client.state == WebOsClient.State.CONNECTED
        if (key == "POWER") {
            when {
                connected -> client.request("ssap://system/turnOff")
                ir.available -> sendIr(key)
                else -> toast("Bu model (2014) Wi-Fi ile açılamıyor. TV'nin altındaki joystick tuşuyla açın.")
            }
            return
        }

        if (!connected) {
            if (ir.available) sendIr(key) else {
                toast("Önce TV'ye bağlanın")
                showConnectSheet()
            }
            return
        }

        when (key) {
            "INPUT" -> showInputsSheet()
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
                val mute = !payload.optBoolean("mute")
                client.request("ssap://audio/setMute", JSONObject().put("mute", mute))
                toast(if (mute) "Ses kapatıldı" else "Ses açıldı", short = true)
            }
        }
    }

    private fun updateModeHint() {
        modeHint.text = if (Prefs.irMode(this)) "IR modu · kızılötesi" else "LG webOS · Wi-Fi"
    }

    // ---- Connection state ----

    override fun onStateChanged(state: WebOsClient.State, message: String?) {
        val host = client.host ?: ""
        val (text, color) = when (state) {
            WebOsClient.State.DISCONNECTED ->
                (if (message != null) "Bağlanamadı · dokun" else "Bağlı değil · dokun") to R.color.muted
            WebOsClient.State.CONNECTING -> "Bağlanıyor…" to R.color.yellow
            WebOsClient.State.WAITING_FOR_PAIRING -> "Onay bekleniyor" to R.color.yellow
            WebOsClient.State.CONNECTED -> "LG TV · $host" to R.color.green
        }
        statusChip.text = text
        statusChip.contentColor = getColor(color)

        if (state == WebOsClient.State.WAITING_FOR_PAIRING) showPairingSheet()
        else { pairingSheet?.dismiss(); pairingSheet = null }

        if (state == WebOsClient.State.CONNECTED) { connectSheet?.dismiss(); connectSheet = null }
    }

    private fun showPairingSheet() {
        if (pairingSheet != null || isFinishing) return
        val content = layoutInflater.inflate(R.layout.sheet_message, null)
        content.findViewById<TextView>(R.id.msgText).text =
            "TV ekranında bir eşleştirme isteği çıktı.\n\n" +
            "Kumandanız olmadığı için TV'nin alt-orta kısmındaki joystick tuşunu kullanın: " +
            "\"Evet\"e gelip tuşa basın.\n\nBu yalnızca bir kez gerekir."
        pairingSheet = Sheet(this, "TV'de onaylayın", content).apply {
            setOnDismissListener { pairingSheet = null }
            show()
        }
    }

    private fun showConnectSheet() {
        if (connectSheet != null || isFinishing) return
        val content = layoutInflater.inflate(R.layout.sheet_connect, null)
        val status = content.findViewById<TextView>(R.id.scanStatus)
        val list = content.findViewById<LinearLayout>(R.id.tvList)
        val ipField = content.findViewById<EditText>(R.id.ipField)
        ipField.setText(Prefs.lastHost(this) ?: "")

        val sheet = Sheet(this, "TV'ye bağlan", content)
        connectSheet = sheet
        sheet.setOnDismissListener { connectSheet = null }

        fun scan() {
            status.text = "Ağdaki LG TV'ler aranıyor…"
            list.removeAllViews()
            Discovery.search(this) { tvs ->
                if (!sheet.isShowing) return@search
                status.text = if (tvs.isEmpty())
                    "TV bulunamadı. TV açık ve aynı Wi-Fi ağında mı?" else "Bulunan TV'ler:"
                for (tv in tvs) {
                    list.addView(rowButton(tv.name, R.drawable.ic_tv, tv.host) {
                        client.connect(tv.host)
                        status.text = "${tv.host} adresine bağlanılıyor…"
                    })
                }
            }
        }

        content.findViewById<View>(R.id.btnRescan).setOnClickListener { scan() }
        content.findViewById<View>(R.id.btnConnectIp).setOnClickListener {
            val ip = ipField.text.toString().trim()
            if (ip.isEmpty()) return@setOnClickListener
            client.connect(ip)
            status.text = "$ip adresine bağlanılıyor…"
        }
        sheet.show()
        scan()
    }

    // ---- Sheets ----

    private fun requireConnected(): Boolean {
        if (client.state == WebOsClient.State.CONNECTED) return true
        toast("Önce TV'ye bağlanın")
        showConnectSheet()
        return false
    }

    private fun showAdvancedSheet() {
        val content = layoutInflater.inflate(R.layout.sheet_advanced, null)
        val sheet = Sheet(this, "Gelişmiş", content)
        wireKeys(content)

        val irButton = content.findViewById<KeyButton>(R.id.btnIrMode)
        fun refreshIr() {
            val on = Prefs.irMode(this)
            irButton.selectedLook = on
            irButton.text = if (on) "IR modu açık" else "IR modu (kızılötesi)"
        }
        refreshIr()
        irButton.setOnClickListener {
            if (!ir.available) {
                toast("Bu telefonda kızılötesi (IR) verici yok")
            } else {
                Prefs.setIrMode(this, !Prefs.irMode(this))
                refreshIr()
                updateModeHint()
            }
        }
        content.findViewById<View>(R.id.btnChangeTv).setOnClickListener {
            sheet.dismiss()
            showConnectSheet()
        }
        content.findViewById<View>(R.id.btnDisconnect).setOnClickListener {
            sheet.dismiss()
            client.disconnect()
        }
        sheet.show()
    }

    private fun showKeyboardSheet() {
        if (!requireConnected()) return
        val content = layoutInflater.inflate(R.layout.sheet_keyboard, null)
        val field = content.findViewById<EditText>(R.id.kbField)
        val sheet = Sheet(this, "Klavye", content)

        // Mirror every edit to the TV's on-screen text box as it happens.
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s == null) return
                if (before > 0) {
                    client.request("ssap://com.webos.service.ime/deleteCharacters",
                        JSONObject().put("count", before))
                }
                if (count > 0) {
                    client.request("ssap://com.webos.service.ime/insertText",
                        JSONObject().put("text", s.subSequence(start, start + count).toString()).put("replace", 0))
                }
            }
        })
        val enter = {
            client.request("ssap://com.webos.service.ime/sendEnterKey")
            sheet.dismiss()
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { enter(); true } else false
        }
        content.findViewById<View>(R.id.kbEnter).setOnClickListener { enter() }
        content.findViewById<View>(R.id.kbDelete).setOnClickListener {
            if (field.text.isNotEmpty()) {
                field.text.delete(field.text.length - 1, field.text.length) // watcher sends the delete
            } else {
                client.request("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", 1))
            }
        }
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        sheet.show()
        field.requestFocus()
    }

    private fun showAppsSheet() {
        if (!requireConnected()) return
        client.request("ssap://com.webos.applicationManager/listLaunchPoints") { payload, err ->
            val list = payload?.optJSONArray("launchPoints")
            if (err == null && list != null && list.length() > 0) {
                showAppList(list)
            } else {
                client.request("ssap://com.webos.applicationManager/listApps") { p2, e2 ->
                    val apps = p2?.optJSONArray("apps")
                    if (e2 == null && apps != null) showAppList(apps)
                    else toast("Uygulama listesi alınamadı: ${e2 ?: err}")
                }
            }
        }
    }

    private fun showAppList(arr: JSONArray) {
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty() || o.optBoolean("hidden", false)) null
            else o.optString("title", id) to id
        }.sortedBy { it.first.lowercase() }
        listSheet("Uygulamalar", items.map { it.first }, R.drawable.ic_apps) { which ->
            client.request("ssap://system.launcher/launch", JSONObject().put("id", items[which].second))
        }
    }

    private fun showInputsSheet() {
        client.request("ssap://tv/getExternalInputList") { payload, err ->
            val devices = payload?.optJSONArray("devices")
            if (err != null || devices == null || devices.length() == 0) {
                client.sendButton("INPUT") // fall back to the TV's own input picker
                return@request
            }
            val items = (0 until devices.length()).mapNotNull { i ->
                val d = devices.optJSONObject(i) ?: return@mapNotNull null
                d.optString("label", d.optString("id")) to d.optString("id")
            }
            listSheet("Kaynak seçin", items.map { it.first }, R.drawable.ic_input) { which ->
                client.request("ssap://tv/switchInput", JSONObject().put("inputId", items[which].second))
            }
        }
    }

    private fun listSheet(title: String, labels: List<String>, icon: Int, onPick: (Int) -> Unit) {
        if (isFinishing) return
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(this, title, list)
        labels.forEachIndexed { i, label ->
            list.addView(rowButton(label, icon) { sheet.dismiss(); onPick(i) })
        }
        sheet.show()
    }

    private fun rowButton(label: String, iconRes: Int, description: String? = null, onClick: () -> Unit): View {
        val m = (5 * resources.displayMetrics.density).toInt()
        return KeyButton(this).apply {
            this.icon = getDrawable(iconRes)
            text = if (description != null && description != label) "$label  ·  $description" else label
            setAlignStart()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (60 * resources.displayMetrics.density).toInt(),
            ).apply { setMargins(m, m, m, m) }
        }
    }

    private fun toast(msg: String, short: Boolean = false) =
        Toast.makeText(this, msg, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
}
