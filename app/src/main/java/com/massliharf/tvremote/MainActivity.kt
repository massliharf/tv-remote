package com.massliharf.tvremote

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), Controller.Listener {

    private var controller: Controller? = null
    private lateinit var ir: IrRemote
    private lateinit var statusChip: KeyButton
    private lateinit var btnPad: KeyButton
    private lateinit var dpad: DpadView
    private lateinit var touchpad: TouchpadView
    private lateinit var modeHint: TextView
    private lateinit var deviceBar: LinearLayout
    private lateinit var deviceBarScroll: View

    private var confirmSheet: Sheet? = null
    private var pinSheet: Sheet? = null
    private var connectSheet: Sheet? = null
    private var pinError: TextView? = null

    private val isLg get() = controller?.device?.type != Device.Type.ANDROID_TV

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(android.R.id.content)
        Fonts.apply(root)

        ir = IrRemote(this)
        statusChip = findViewById(R.id.statusChip)
        btnPad = findViewById(R.id.btnPad)
        dpad = findViewById(R.id.dpad)
        touchpad = findViewById(R.id.touchpad)
        modeHint = findViewById(R.id.modeHint)
        deviceBar = findViewById(R.id.deviceBar)
        deviceBarScroll = findViewById(R.id.deviceBarScroll)

        wireKeys(root)
        dpad.onKey = { press(it) }
        findViewById<RockerView>(R.id.volRocker).onKey = { press(it) }
        findViewById<RockerView>(R.id.chRocker).onKey = { press(it) }

        statusChip.setOnClickListener { showDevicesSheet() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showAdvancedSheet() }
        findViewById<View>(R.id.btnKeyboard).setOnClickListener { showKeyboardSheet() }
        findViewById<View>(R.id.btnApps).setOnClickListener { showAppsSheet() }
        btnPad.setOnClickListener { setTouchpad(touchpad.visibility != View.VISIBLE) }
        touchpad.listener = object : TouchpadView.Listener {
            override fun onMove(dx: Int, dy: Int) { controller?.move(dx, dy) }
            override fun onTap() { controller?.click() }
            override fun onScroll(dx: Int, dy: Int) { controller?.scroll(dx, dy) }
        }

        Prefs.activeDevice(this)?.let { selectDevice(it, connect = false) }
        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        val c = controller
        if (c == null) showConnectSheet()
        else if (c.state == Controller.State.DISCONNECTED) c.connect()
    }

    override fun onStop() {
        super.onStop()
        controller?.disconnect()
    }

    // ---- Devices ----

    /** Makes [device] the one the remote drives, disconnecting the previous one. */
    private fun selectDevice(device: Device, connect: Boolean = true) {
        if (controller?.device?.id == device.id) {
            if (connect && controller?.state == Controller.State.DISCONNECTED) controller?.connect()
            return
        }
        controller?.disconnect()
        Prefs.addDevice(this, device)
        Prefs.setActiveId(this, device.id)
        controller = when (device.type) {
            Device.Type.LG -> LgController(this, device, this)
            Device.Type.ANDROID_TV -> AtvController(this, device, this)
        }
        setTouchpad(false)
        refreshUi()
        if (connect) controller?.connect()
    }

    private fun refreshUi() {
        val c = controller
        btnPad.isEnabled = c?.hasPointer ?: true
        updateModeHint()
        render(c, c?.state ?: Controller.State.DISCONNECTED, null)
        buildDeviceBar()
    }

    private fun buildDeviceBar() {
        val devices = Prefs.devices(this)
        deviceBarScroll.visibility = if (devices.size >= 2) View.VISIBLE else View.GONE
        deviceBar.removeAllViews()
        val d = resources.displayMetrics.density
        for (dev in devices) {
            deviceBar.addView(KeyButton(this).apply {
                icon = getDrawable(iconFor(dev))
                text = dev.name
                selectedLook = dev.id == controller?.device?.id
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    selectDevice(dev)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (48 * d).toInt(),
            ).apply { marginEnd = (10 * d).toInt() })
        }
    }

    private fun iconFor(d: Device) = if (d.type == Device.Type.ANDROID_TV) R.drawable.ic_android else R.drawable.ic_tv

    private fun setTouchpad(on: Boolean) {
        val show = on && controller?.hasPointer != false
        touchpad.visibility = if (show) View.VISIBLE else View.GONE
        dpad.visibility = if (show) View.GONE else View.VISIBLE
        btnPad.text = if (show) "Yön tuşu" else "Touchpad"
        btnPad.icon = getDrawable(if (show) R.drawable.ic_dpad else R.drawable.ic_touch)
    }

    // ---- Key handling ----

    /** Every KeyButton with a String tag sends that key when tapped. */
    private fun wireKeys(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) wireKeys(view.getChildAt(i))
            return
        }
        val key = view.tag as? String ?: return
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            press(key)
        }
    }

    private fun press(key: String) {
        val c = controller
        // IR codes are LG codes, so IR mode only applies to the LG TV.
        if (isLg && Prefs.irMode(this)) {
            sendIr(key)
            return
        }
        if (c == null || c.state != Controller.State.CONNECTED) {
            when {
                isLg && ir.available -> sendIr(key)
                isLg && key == "POWER" ->
                    toast("Bu model (2014) Wi-Fi ile açılamıyor. TV'nin altındaki joystick tuşuyla açın.")
                else -> {
                    toast("Önce cihaza bağlanın")
                    if (c == null) showConnectSheet() else c.connect()
                }
            }
            return
        }
        if (key == "INPUT") showInputsSheet() else c.sendKey(key)
    }

    private fun sendIr(key: String) {
        val code = IrRemote.CODES[key]
        when {
            !ir.available -> toast("Bu telefonda kızılötesi (IR) verici yok")
            code == null -> toast("Bu tuş IR modunda desteklenmiyor")
            else -> ir.send(code)
        }
    }

    private fun updateModeHint() {
        modeHint.text = when {
            controller?.device?.type == Device.Type.ANDROID_TV -> "Android TV · Wi-Fi"
            Prefs.irMode(this) -> "IR modu · kızılötesi"
            else -> "LG webOS · Wi-Fi"
        }
    }

    // ---- Connection state ----

    override fun onStateChanged(controller: Controller, state: Controller.State, message: String?) {
        if (controller !== this.controller) return // a device we switched away from
        render(controller, state, message)
    }

    private fun render(controller: Controller?, state: Controller.State, message: String?) {
        val name = controller?.device?.name ?: "Cihaz"
        val (label, color) = when (state) {
            Controller.State.DISCONNECTED -> when {
                controller == null -> "Cihaz ekle · dokun"
                message != null -> "$name · bağlanamadı"
                else -> "$name · bağlı değil"
            } to R.color.muted
            Controller.State.CONNECTING -> "$name · bağlanıyor…" to R.color.yellow
            Controller.State.CONFIRM_ON_TV, Controller.State.ENTER_PIN -> "$name · eşleştiriliyor" to R.color.yellow
            Controller.State.CONNECTED -> name to R.color.green
        }
        statusChip.text = label
        statusChip.icon = getDrawable(controller?.device?.let { iconFor(it) } ?: R.drawable.ic_tv)
        statusChip.contentColor = getColor(color)

        if (state == Controller.State.CONFIRM_ON_TV) showConfirmSheet()
        else { confirmSheet?.dismiss(); confirmSheet = null }

        if (state == Controller.State.ENTER_PIN) showPinSheet()
        else if (pinSheet != null) {
            if (state == Controller.State.DISCONNECTED && message != null) {
                pinError?.apply { text = message; visibility = View.VISIBLE }
            } else {
                pinSheet?.dismiss(); pinSheet = null
            }
        }

        if (state == Controller.State.CONNECTED) { connectSheet?.dismiss(); connectSheet = null }
        if (state == Controller.State.DISCONNECTED && message != null && pinSheet == null && controller != null) {
            toast("${controller.device.name}: $message", short = true)
        }
    }

    private fun showConfirmSheet() {
        if (confirmSheet != null || isFinishing) return
        val content = layoutInflater.inflate(R.layout.sheet_message, null)
        content.findViewById<TextView>(R.id.msgText).text =
            "TV ekranında bir eşleştirme isteği çıktı.\n\n" +
            "Kumandanız olmadığı için TV'nin alt-orta kısmındaki joystick tuşunu kullanın: " +
            "\"Evet\"e gelip tuşa basın.\n\nBu yalnızca bir kez gerekir."
        confirmSheet = Sheet(this, "TV'de onaylayın", content).apply {
            setOnDismissListener { confirmSheet = null }
            show()
        }
    }

    private fun showPinSheet() {
        pinError?.visibility = View.GONE
        if (pinSheet != null || isFinishing) return
        val content = layoutInflater.inflate(R.layout.sheet_pin, null)
        val field = content.findViewById<EditText>(R.id.pinField)
        val error = content.findViewById<TextView>(R.id.pinError)
        pinError = error
        val sheet = Sheet(this, "Eşleştirme kodu", content)
        pinSheet = sheet
        sheet.setOnDismissListener { pinSheet = null; pinError = null }

        val submit = submit@{
            val pin = field.text.toString().trim()
            val c = controller ?: return@submit
            if (c.state != Controller.State.ENTER_PIN) {
                // The previous attempt ended; start over to get a fresh code.
                error.text = "Yeni kod isteniyor…"
                error.visibility = View.VISIBLE
                field.setText("")
                c.connect()
                return@submit
            }
            if (pin.length != 6) {
                error.text = "Kod 6 karakter olmalı"
                error.visibility = View.VISIBLE
            } else if (!c.submitPin(pin)) {
                error.text = "Kod yanlış görünüyor, tekrar kontrol edin"
                error.visibility = View.VISIBLE
            } else {
                error.visibility = View.GONE
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        content.findViewById<View>(R.id.btnPin).setOnClickListener { submit() }
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        sheet.show()
        field.requestFocus()
    }

    // ---- Device / connect sheets ----

    private fun showDevicesSheet() {
        val devices = Prefs.devices(this)
        if (devices.isEmpty()) {
            showConnectSheet()
            return
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(this, "Cihazlar", list)
        for (dev in devices) {
            val active = dev.id == controller?.device?.id
            val row = rowButton(dev.name, iconFor(dev), dev.host) {
                sheet.dismiss()
                selectDevice(dev)
            }
            (row as KeyButton).selectedLook = active
            row.setOnLongClickListener {
                Prefs.removeDevice(this, dev)
                if (active) {
                    controller?.disconnect()
                    controller = null
                    Prefs.activeDevice(this)?.let { selectDevice(it) }
                }
                refreshUi()
                sheet.dismiss()
                toast("${dev.name} silindi", short = true)
                true
            }
            list.addView(row)
        }
        list.addView(rowButton("Yeni cihaz ekle", R.drawable.ic_add_circle) {
            sheet.dismiss()
            showConnectSheet()
        })
        list.addView(TextView(this).apply {
            text = "Silmek için bir cihaza basılı tutun."
            setTextColor(getColor(R.color.muted))
            textSize = 13f
            setPadding((6 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        Fonts.apply(list)
        sheet.show()
    }

    private fun showConnectSheet() {
        if (connectSheet != null || isFinishing) return
        val content = layoutInflater.inflate(R.layout.sheet_connect, null)
        val status = content.findViewById<TextView>(R.id.scanStatus)
        val list = content.findViewById<LinearLayout>(R.id.tvList)
        val ipField = content.findViewById<EditText>(R.id.ipField)

        val sheet = Sheet(this, "Cihaz ekle", content)
        connectSheet = sheet
        sheet.setOnDismissListener { connectSheet = null }

        fun scan() {
            status.text = "Ağdaki cihazlar aranıyor…"
            list.removeAllViews()
            Discovery.search(this) { found ->
                if (!sheet.isShowing) return@search
                status.text = if (found.isEmpty())
                    "Cihaz bulunamadı. Açık ve aynı Wi-Fi ağında mı?" else "Bulunan cihazlar:"
                for (dev in found) {
                    val kind = if (dev.type == Device.Type.LG) "LG webOS" else "Android TV"
                    list.addView(rowButton(dev.name, iconFor(dev), "$kind · ${dev.host}") {
                        status.text = "${dev.name} cihazına bağlanılıyor…"
                        selectDevice(dev)
                    })
                }
            }
        }

        fun connectIp(type: Device.Type) {
            val ip = ipField.text.toString().trim()
            if (ip.isEmpty()) return
            val name = if (type == Device.Type.LG) "LG TV" else "Android TV"
            status.text = "$ip adresine bağlanılıyor…"
            selectDevice(Device(type, ip, name))
        }

        content.findViewById<View>(R.id.btnRescan).setOnClickListener { scan() }
        content.findViewById<View>(R.id.btnIpLg).setOnClickListener { connectIp(Device.Type.LG) }
        content.findViewById<View>(R.id.btnIpAtv).setOnClickListener { connectIp(Device.Type.ANDROID_TV) }
        sheet.show()
        scan()
    }

    // ---- Other sheets ----

    private fun requireConnected(): Controller? {
        val c = controller
        if (c != null && c.state == Controller.State.CONNECTED) return c
        toast("Önce cihaza bağlanın")
        if (c == null) showConnectSheet() else c.connect()
        return null
    }

    private fun showAdvancedSheet() {
        val content = layoutInflater.inflate(R.layout.sheet_advanced, null)
        val sheet = Sheet(this, "Gelişmiş", content)
        wireKeys(content)

        val irButton = content.findViewById<KeyButton>(R.id.btnIrMode)
        irButton.visibility = if (isLg) View.VISIBLE else View.GONE
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
            showDevicesSheet()
        }
        content.findViewById<View>(R.id.btnDisconnect).setOnClickListener {
            sheet.dismiss()
            controller?.disconnect()
        }
        sheet.show()
    }

    private fun showKeyboardSheet() {
        val c = requireConnected() ?: return
        val content = layoutInflater.inflate(R.layout.sheet_keyboard, null)
        val field = content.findViewById<EditText>(R.id.kbField)
        val sheet = Sheet(this, "Klavye", content)

        // Mirror every edit to the TV's text box as it happens.
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s == null) return
                c.textChanged(s.toString(), before, s.subSequence(start, start + count).toString())
            }
        })
        val enter = {
            c.sendEnter()
            sheet.dismiss()
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { enter(); true } else false
        }
        content.findViewById<View>(R.id.kbEnter).setOnClickListener { enter() }
        content.findViewById<View>(R.id.kbDelete).setOnClickListener {
            if (field.text.isNotEmpty()) {
                field.text.delete(field.text.length - 1, field.text.length) // the watcher sends it
            } else {
                c.deleteChar()
            }
        }
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        sheet.show()
        field.requestFocus()
    }

    private fun showAppsSheet() {
        val c = requireConnected() ?: return
        c.listApps { apps, err ->
            if (apps == null) {
                toast("Uygulama listesi alınamadı: $err")
                return@listApps
            }
            listSheet("Uygulamalar", apps.map { it.first }, R.drawable.ic_apps) { which ->
                c.launchApp(apps[which].second)
            }
        }
    }

    private fun showInputsSheet() {
        val c = controller ?: return
        c.listInputs { inputs ->
            if (inputs == null) {
                c.sendKey("INPUT") // the device's own input picker
                return@listInputs
            }
            listSheet("Kaynak seçin", inputs.map { it.first }, R.drawable.ic_input) { which ->
                c.switchInput(inputs[which].second)
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
        val d = resources.displayMetrics.density
        val m = (5 * d).toInt()
        return KeyButton(this).apply {
            icon = getDrawable(iconRes)
            text = if (description != null && description != label) "$label  ·  $description" else label
            setAlignStart()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (60 * d).toInt(),
            ).apply { setMargins(m, m, m, m) }
        }
    }

    private fun toast(msg: String, short: Boolean = false) =
        Toast.makeText(this, msg, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
}
