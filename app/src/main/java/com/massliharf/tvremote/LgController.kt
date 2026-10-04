package com.massliharf.tvremote

import android.content.Context
import org.json.JSONObject

/** LG webOS TV over the SSAP WebSocket protocol. */
class LgController(
    context: Context,
    override val device: Device,
    private val listener: Controller.Listener,
) : Controller {

    private val client = WebOsClient(context, object : WebOsClient.Listener {
        override fun onStateChanged(state: WebOsClient.State, message: String?) {
            listener.onStateChanged(this@LgController, map(state), message)
        }
    })

    private fun map(s: WebOsClient.State) = when (s) {
        WebOsClient.State.DISCONNECTED -> Controller.State.DISCONNECTED
        WebOsClient.State.CONNECTING -> Controller.State.CONNECTING
        WebOsClient.State.WAITING_FOR_PAIRING -> Controller.State.CONFIRM_ON_TV
        WebOsClient.State.CONNECTED -> Controller.State.CONNECTED
    }

    override val state get() = map(client.state)

    /** Keys with dedicated SSAP endpoints; everything else goes through the pointer socket. */
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
        "POWER" to "ssap://system/turnOff",
    )

    override fun connect() = client.connect(device.host)
    override fun disconnect() = client.disconnect()

    override fun sendKey(key: String) {
        if (key == "MUTE") {
            client.request("ssap://audio/getStatus") { payload, err ->
                if (err != null || payload == null || !payload.has("mute")) client.sendButton("MUTE")
                else client.request("ssap://audio/setMute", JSONObject().put("mute", !payload.optBoolean("mute")))
            }
            return
        }
        val uri = ssapKeys[key]
        if (uri != null) client.request(uri) else client.sendButton(key)
    }

    override fun textChanged(full: String, deleted: Int, inserted: String) {
        if (deleted > 0) {
            client.request("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", deleted))
        }
        if (inserted.isNotEmpty()) {
            client.request("ssap://com.webos.service.ime/insertText",
                JSONObject().put("text", inserted).put("replace", 0))
        }
    }

    override fun sendEnter() = client.request("ssap://com.webos.service.ime/sendEnterKey")

    override fun deleteChar() =
        client.request("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", 1))

    override val hasPointer get() = true
    override fun move(dx: Int, dy: Int) = client.move(dx, dy)
    override fun click() = client.click()
    override fun scroll(dx: Int, dy: Int) = client.scroll(dx, dy)

    override fun listApps(callback: (List<Pair<String, String>>?, String?) -> Unit) {
        fun parse(arr: org.json.JSONArray) = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty() || o.optBoolean("hidden", false)) null else o.optString("title", id) to id
        }.sortedBy { it.first.lowercase() }

        client.request("ssap://com.webos.applicationManager/listLaunchPoints") { payload, err ->
            val list = payload?.optJSONArray("launchPoints")
            if (err == null && list != null && list.length() > 0) {
                callback(parse(list), null)
            } else {
                client.request("ssap://com.webos.applicationManager/listApps") { p2, e2 ->
                    val apps = p2?.optJSONArray("apps")
                    if (e2 == null && apps != null) callback(parse(apps), null) else callback(null, e2 ?: err)
                }
            }
        }
    }

    override fun launchApp(id: String) =
        client.request("ssap://system.launcher/launch", JSONObject().put("id", id))

    override fun listInputs(callback: (List<Pair<String, String>>?) -> Unit) {
        client.request("ssap://tv/getExternalInputList") { payload, err ->
            val devices = payload?.optJSONArray("devices")
            if (err != null || devices == null || devices.length() == 0) {
                callback(null)
                return@request
            }
            callback((0 until devices.length()).mapNotNull { i ->
                val d = devices.optJSONObject(i) ?: return@mapNotNull null
                d.optString("label", d.optString("id")) to d.optString("id")
            })
        }
    }

    override fun switchInput(id: String) =
        client.request("ssap://tv/switchInput", JSONObject().put("inputId", id))
}
