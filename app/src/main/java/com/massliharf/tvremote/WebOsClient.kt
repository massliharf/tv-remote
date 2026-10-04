package com.massliharf.tvremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal client for the LG webOS "SSAP" second-screen protocol (ws://<tv>:3000).
 * The 42LB652V (2014) runs webOS 1.x, which speaks this protocol over plain WebSocket.
 *
 * All callbacks are delivered on the main thread.
 */
class WebOsClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onStateChanged(state: State, message: String? = null)
    }

    enum class State { DISCONNECTED, CONNECTING, WAITING_FOR_PAIRING, CONNECTED }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var pointerRequested = false
    private val pendingButtons = ArrayList<String>()
    private val nextId = AtomicInteger(1)
    private val callbacks = HashMap<String, (JSONObject?, String?) -> Unit>()
    private var triedUnsigned = false

    var host: String? = null
        private set
    var state = State.DISCONNECTED
        private set

    fun connect(host: String) {
        disconnect()
        this.host = host
        triedUnsigned = false
        setState(State.CONNECTING)
        val request = Request.Builder().url("ws://$host:3000/").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { register(signed = true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { handleMessage(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (webSocket == socket) onLost(null) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (webSocket == socket) onLost(t.message ?: t.javaClass.simpleName) }
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, null)
        socket = null
        closePointer()
        callbacks.clear()
        if (state != State.DISCONNECTED) setState(State.DISCONNECTED)
    }

    private fun onLost(error: String?) {
        socket = null
        closePointer()
        callbacks.clear()
        setState(State.DISCONNECTED, error)
    }

    private fun closePointer() {
        pointerSocket?.close(1000, null)
        pointerSocket = null
        pointerRequested = false
        pendingButtons.clear()
    }

    private fun setState(s: State, message: String? = null) {
        state = s
        listener.onStateChanged(s, message)
    }

    // ---- Pairing ----

    private fun register(signed: Boolean) {
        val pairing = JSONObject(
            context.assets.open("pairing.json").bufferedReader().use { it.readText() }
        )
        if (!signed) {
            // Newer firmware rejects the old signed manifest; fall back to the unsigned form.
            val manifest = pairing.getJSONObject("manifest")
            manifest.remove("signed")
            manifest.put("appVersion", "1.0")
            manifest.getJSONArray("permissions")
                .put("CONTROL_INPUT_TEXT").put("CONTROL_MOUSE_AND_KEYBOARD")
        }
        Prefs.clientKey(context, host)?.let { pairing.put("client-key", it) }

        val msg = JSONObject()
            .put("type", "register")
            .put("id", "register_0")
            .put("payload", pairing)
        socket?.send(msg.toString())
    }

    private fun handleMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        val id = msg.optString("id")
        val type = msg.optString("type")
        val payload = msg.optJSONObject("payload")

        if (id == "register_0") {
            when (type) {
                "response" ->
                    if (payload?.optString("pairingType") == "PROMPT") {
                        setState(State.WAITING_FOR_PAIRING)
                    }
                "registered" -> {
                    payload?.optString("client-key")?.takeIf { it.isNotEmpty() }?.let {
                        Prefs.setClientKey(context, host, it)
                    }
                    Prefs.setLastHost(context, host)
                    setState(State.CONNECTED)
                }
                "error" -> {
                    val err = msg.optString("error")
                    if (!triedUnsigned && err.contains("blacklisted", ignoreCase = true)) {
                        triedUnsigned = true
                        register(signed = false)
                    } else {
                        // A stale client key is rejected; forget it so the next attempt prompts again.
                        Prefs.setClientKey(context, host, null)
                        setState(State.DISCONNECTED, err)
                        socket?.close(1000, null)
                    }
                }
            }
            return
        }

        val cb = callbacks.remove(id) ?: return
        if (type == "error" || payload?.optBoolean("returnValue", true) == false) {
            cb(payload, msg.optString("error").ifEmpty { payload?.optString("errorText") ?: "error" })
        } else {
            cb(payload, null)
        }
    }

    // ---- Requests ----

    fun request(
        uri: String,
        payload: JSONObject = JSONObject(),
        callback: ((JSONObject?, String?) -> Unit)? = null,
    ) {
        val ws = socket
        if (ws == null || state != State.CONNECTED) {
            callback?.invoke(null, "not connected")
            return
        }
        val id = "req_" + nextId.getAndIncrement()
        if (callback != null) callbacks[id] = callback
        val msg = JSONObject()
            .put("type", "request")
            .put("id", id)
            .put("uri", uri)
            .put("payload", payload)
        ws.send(msg.toString())
    }

    // ---- Pointer / button input socket ----

    /** Sends a remote-control key such as UP, DOWN, ENTER, BACK, HOME, MENU, 0-9, RED ... */
    fun sendButton(name: String) = sendPointer("type:button\nname:$name\n\n")

    fun move(dx: Int, dy: Int) = sendPointer("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n", queue = false)

    fun click() = sendPointer("type:click\n\n")

    fun scroll(dx: Int, dy: Int) = sendPointer("type:scroll\ndx:$dx\ndy:$dy\n\n", queue = false)

    private fun sendPointer(frame: String, queue: Boolean = true) {
        val ps = pointerSocket
        if (ps != null) {
            ps.send(frame)
            return
        }
        if (queue) pendingButtons.add(frame)
        openPointerSocket()
    }

    private fun openPointerSocket() {
        if (pointerRequested || state != State.CONNECTED) return
        pointerRequested = true
        request("ssap://com.webos.service.networkinput/getPointerInputSocket") { payload, err ->
            val path = payload?.optString("socketPath")
            if (err != null || path.isNullOrEmpty()) {
                pointerRequested = false
                pendingButtons.clear()
                return@request
            }
            val ws = http.newWebSocket(
                Request.Builder().url(path).build(),
                object : WebSocketListener() {
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        main.post { if (webSocket == pointerSocket) closePointer() }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        main.post { if (webSocket == pointerSocket) closePointer() }
                    }
                },
            )
            pointerSocket = ws
            // OkHttp queues frames until the socket is open, so order is preserved.
            pendingButtons.forEach { ws.send(it) }
            pendingButtons.clear()
        }
    }
}
