package com.massliharf.tvremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.annotation.TargetApi
import kotlin.math.abs

/**
 * Makes the phone a Bluetooth mouse (HID device profile, Android 9+).
 *
 * The Android TV Remote protocol has no pointer messages, but Android TV shows a real mouse
 * cursor for any Bluetooth mouse, so the touchpad drives the box over Bluetooth instead.
 */
@SuppressLint("MissingPermission") // callers check BLUETOOTH_CONNECT first
@TargetApi(Build.VERSION_CODES.P)
class HidMouse(context: Context, private val listener: (State, String?) -> Unit) {

    enum class State { OFF, STARTING, WAITING_FOR_HOST, CONNECTING, CONNECTED }

    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val prefs = app.getSharedPreferences("hid", Context.MODE_PRIVATE)

    private var hid: BluetoothHidDevice? = null
    private var registered = false
    var host: BluetoothDevice? = null
        private set
    var state = State.OFF
        private set

    private var wheelAccum = 0f

    val bluetoothOn get() = adapter?.isEnabled == true

    /** Phones already paired with this one, e.g. the TV box. */
    val bondedDevices: List<BluetoothDevice> get() = adapter?.bondedDevices?.toList() ?: emptyList()

    private fun set(s: State, message: String? = null) {
        state = s
        listener(s, message)
    }

    fun start() {
        if (state != State.OFF) return
        val a = adapter ?: return set(State.OFF, "Bu telefonda Bluetooth yok")
        set(State.STARTING)
        val ok = a.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                register()
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                registered = false
                if (state != State.OFF) set(State.OFF, null)
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) set(State.OFF, "Telefonunuz Bluetooth fare özelliğini desteklemiyor")
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "LG TV Kumanda", "Touchpad", "TV Kumanda",
            BluetoothHidDevice.SUBCLASS1_MOUSE, DESCRIPTOR,
        )
        val ok = h.registerApp(sdp, null, null, app.mainExecutor, object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                this@HidMouse.registered = registered
                if (!registered) {
                    if (state != State.OFF) set(State.OFF, null)
                    return
                }
                val target = pluggedDevice ?: savedHost()
                if (target != null) connect(target) else set(State.WAITING_FOR_HOST)
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        prefs.edit().putString("host", device.address).apply()
                        set(State.CONNECTED)
                    }
                    BluetoothProfile.STATE_CONNECTING -> set(State.CONNECTING)
                    BluetoothProfile.STATE_DISCONNECTED -> if (device == host || host == null) {
                        host = null
                        if (registered) set(State.WAITING_FOR_HOST)
                    }
                }
            }
        })
        if (!ok) set(State.OFF, "Bluetooth fare başlatılamadı")
    }

    private fun savedHost(): BluetoothDevice? {
        val addr = prefs.getString("host", null) ?: return null
        return bondedDevices.firstOrNull { it.address == addr }
    }

    fun connect(device: BluetoothDevice) {
        val h = hid ?: return
        set(State.CONNECTING)
        if (!h.connect(device)) set(State.WAITING_FOR_HOST, "Bağlanılamadı")
    }

    fun stop() {
        val h = hid
        if (h != null) {
            host?.let { h.disconnect(it) }
            if (registered) h.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
        registered = false
        host = null
        if (state != State.OFF) set(State.OFF)
    }

    // ---- Reports: [buttons, dx, dy, wheel] ----

    fun move(dx: Int, dy: Int) {
        var x = dx
        var y = dy
        // Each report carries at most ±127 per axis.
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            report(0, sx, sy, 0)
            x -= sx
            y -= sy
        }
    }

    fun click() {
        report(1, 0, 0, 0)
        report(0, 0, 0, 0)
    }

    /** Two-finger drag: content follows the fingers, like a phone. */
    fun scroll(dy: Int) {
        wheelAccum += dy / 40f
        val steps = wheelAccum.toInt()
        if (abs(steps) >= 1) {
            wheelAccum -= steps
            report(0, 0, 0, steps.coerceIn(-127, 127))
        }
    }

    private fun report(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val h = hid ?: return
        val d = host ?: return
        h.sendReport(d, REPORT_ID, byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))
    }

    companion object {
        private const val REPORT_ID = 1

        /** Standard 3-button mouse with wheel, report ID 1. */
        private val DESCRIPTOR = intArrayOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x02, // Usage (Mouse)
            0xA1, 0x01, // Collection (Application)
            0x85, REPORT_ID, //   Report ID
            0x09, 0x01, //   Usage (Pointer)
            0xA1, 0x00, //   Collection (Physical)
            0x05, 0x09, //     Usage Page (Buttons)
            0x19, 0x01, 0x29, 0x03, // Usage Min 1, Max 3
            0x15, 0x00, 0x25, 0x01, // Logical 0..1
            0x95, 0x03, 0x75, 0x01, // 3 x 1 bit
            0x81, 0x02, //     Input (Data, Var, Abs)
            0x95, 0x01, 0x75, 0x05, // 1 x 5 bits padding
            0x81, 0x03, //     Input (Const)
            0x05, 0x01, //     Usage Page (Generic Desktop)
            0x09, 0x30, 0x09, 0x31, 0x09, 0x38, // X, Y, Wheel
            0x15, 0x81, 0x25, 0x7F, // Logical -127..127
            0x75, 0x08, 0x95, 0x03, // 3 x 8 bits
            0x81, 0x06, //     Input (Data, Var, Rel)
            0xC0, //   End Collection
            0xC0, // End Collection
        ).map { it.toByte() }.toByteArray()
    }
}
