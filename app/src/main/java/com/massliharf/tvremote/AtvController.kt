package com.massliharf.tvremote

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.massliharf.tvremote.atv.AtvClient

/** Android TV / Google TV box (Xiaomi Mi Box, Mi TV Stick ...) over Android TV Remote v2. */
class AtvController(
    context: Context,
    override val device: Device,
    private val listener: Controller.Listener,
) : Controller {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.applicationContext.getSharedPreferences("atv", Context.MODE_PRIVATE)

    private val client = AtvClient(
        store = object : AtvClient.Store {
            override fun get(key: String) = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
            }
        },
        clientName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        listener = object : AtvClient.Listener {
            override fun onState(state: AtvClient.State, message: String?) {
                main.post { listener.onStateChanged(this@AtvController, map(state), message) }
            }
        },
    )

    private fun map(s: AtvClient.State) = when (s) {
        AtvClient.State.DISCONNECTED -> Controller.State.DISCONNECTED
        AtvClient.State.CONNECTING -> Controller.State.CONNECTING
        AtvClient.State.NEED_PIN -> Controller.State.ENTER_PIN
        AtvClient.State.CONNECTED -> Controller.State.CONNECTED
    }

    override val state get() = map(client.state)

    override fun connect() = client.connect(device.host)
    override fun disconnect() = client.disconnect()

    override fun sendKey(key: String) {
        KEYS[key]?.let { client.sendKey(it) }
    }

    override fun textChanged(full: String, deleted: Int, inserted: String) {
        if (full.isEmpty()) {
            repeat(deleted.coerceAtMost(50)) { client.sendKey(KEYCODE_DEL) }
        } else {
            client.setText(full)
        }
    }

    override fun sendEnter() = client.sendKey(KEYCODE_ENTER)
    override fun deleteChar() = client.sendKey(KEYCODE_DEL)

    override fun listApps(callback: (List<Pair<String, String>>?, String?) -> Unit) =
        callback(APPS.map { it.first to "market://launch?id=${it.second}" }, null)

    override fun launchApp(id: String) = client.launchApp(id)

    override fun submitPin(pin: String) = client.submitPin(pin)

    companion object {
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67

        /** Remote key names -> Android KeyEvent codes. */
        val KEYS = mapOf(
            "POWER" to 26, "HOME" to 3, "BACK" to 4, "EXIT" to 4,
            "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "ENTER" to 23,
            "VOLUMEUP" to 24, "VOLUMEDOWN" to 25, "MUTE" to 164,
            "CHANNELUP" to 166, "CHANNELDOWN" to 167,
            "MENU" to 176, "QMENU" to 82, "INPUT" to 178, "INFO" to 165, "GUIDE" to 172,
            "PLAY" to 126, "PAUSE" to 127, "STOP" to 86, "REWIND" to 89, "FASTFORWARD" to 90,
            "RED" to 183, "GREEN" to 184, "YELLOW" to 185, "BLUE" to 186,
            "0" to 7, "1" to 8, "2" to 9, "3" to 10, "4" to 11,
            "5" to 12, "6" to 13, "7" to 14, "8" to 15, "9" to 16,
        )

        /** Android TV has no "list apps" call, so offer the popular ones by package. */
        val APPS = listOf(
            "YouTube" to "com.google.android.youtube.tv",
            "Netflix" to "com.netflix.ninja",
            "Prime Video" to "com.amazon.amazonvideo.livingroom",
            "Disney+" to "com.disney.disneyplus",
            "Spotify" to "com.spotify.tv.android",
            "Kodi" to "org.xbmc.kodi",
            "Google Play Store" to "com.android.vending",
        )
    }
}
