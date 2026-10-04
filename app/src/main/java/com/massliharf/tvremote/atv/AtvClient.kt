package com.massliharf.tvremote.atv

import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Client for the Android TV Remote v2 protocol used by Android TV / Google TV devices such as
 * the Xiaomi Mi Box and Mi TV Stick (the same protocol as Google's own TV remote app).
 *
 * - Port 6467: one-time pairing. The TV shows a 6-digit hex code that the user types in.
 * - Port 6466: the remote-control session (key presses, app links, text input).
 *
 * Both are TLS with a self-signed client certificate. Plain JVM code so it can be tested
 * off-device; callbacks arrive on background threads.
 */
class AtvClient(
    private val store: Store,
    private val clientName: String,
    private val listener: Listener,
) {
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    interface Listener {
        fun onState(state: State, message: String?)
    }

    enum class State { DISCONNECTED, CONNECTING, NEED_PIN, CONNECTED }

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile var host: String? = null
        private set

    private val writer = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    @Volatile private var remoteSocket: SSLSocket? = null
    @Volatile private var pairSocket: SSLSocket? = null
    @Volatile private var serverCert: X509Certificate? = null
    @Volatile private var identity: AtvCrypto.Identity? = null
    @Volatile private var imeCounter = 0L
    @Volatile private var fieldCounter = 0L
    @Volatile private var activeFeatures = FEATURES

    fun connect(host: String) {
        disconnect()
        this.host = host
        val gen = generation.incrementAndGet()
        setState(gen, State.CONNECTING, null)
        Thread({ runRemote(gen, allowPairing = true) }, "atv-remote").start()
    }

    fun disconnect() {
        generation.incrementAndGet()
        val sockets = listOf(remoteSocket, pairSocket)
        Thread { sockets.forEach { closeQuietly(it) } }.start() // TLS close does I/O
        remoteSocket = null
        pairSocket = null
        if (state != State.DISCONNECTED) {
            state = State.DISCONNECTED
            listener.onState(State.DISCONNECTED, null)
        }
    }

    // ---- Remote session (6466) ----

    private fun pairedKey() = "atv_paired_$host"

    private fun runRemote(gen: Int, allowPairing: Boolean) {
        val h = host ?: return
        if (store.get(pairedKey()) != "1") {
            if (allowPairing) runPairing(gen) else setState(gen, State.DISCONNECTED, "Eşleştirme başarısız")
            return
        }
        var reachable = false
        var configured = false
        var error: String? = null
        try {
            val tcp = Socket()
            tcp.connect(InetSocketAddress(h, 6466), 5000)
            reachable = true
            val s = tls(tcp, h, 6466)
            s.soTimeout = 20_000 // the TV pings every ~5 s
            remoteSocket = s
            val input = BufferedInputStream(s.inputStream)
            while (gen == generation.get()) {
                val frame = readFrame(input) ?: break
                if (handleRemote(gen, ProtoMessage.parse(frame))) configured = true
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        if (gen != generation.get()) return
        closeQuietly(remoteSocket)
        remoteSocket = null
        if (reachable && !configured && allowPairing) {
            // The TV accepted the connection but not our certificate: pair (again).
            store.put(pairedKey(), null)
            runPairing(gen)
            return
        }
        setState(gen, State.DISCONNECTED, if (reachable) error else "Cihaza ulaşılamadı")
    }

    /** Returns true once the TV has accepted us (sent remote_configure). */
    private fun handleRemote(gen: Int, msg: ProtoMessage): Boolean {
        when {
            msg.has(1) -> { // remote_configure
                val serverFeatures = msg.msg(1)?.int(1) ?: 0L
                activeFeatures = if (serverFeatures == 0L) FEATURES else FEATURES and serverFeatures
                send(remoteSocket, ProtoWriter().msg(1, ProtoWriter()
                    .int(1, activeFeatures)
                    .msg(2, ProtoWriter()
                        .int(3, 1)
                        .string(4, "1")
                        .string(5, "atvremote")
                        .string(6, "1.0.0"))))
                return true
            }
            msg.has(2) -> { // remote_set_active: the session is ready for commands
                send(remoteSocket, ProtoWriter().msg(2, ProtoWriter().int(1, activeFeatures)))
                setState(gen, State.CONNECTED, null)
            }
            msg.has(8) -> { // ping
                val v = msg.msg(8)?.int(1) ?: 0L
                send(remoteSocket, ProtoWriter().msg(9, ProtoWriter().int(1, v)))
            }
            msg.has(21) -> msg.msg(21)?.let { // IME state, needed for text input
                imeCounter = it.int(1)
                fieldCounter = it.int(2)
            }
        }
        return false
    }

    fun sendKey(keyCode: Int) {
        send(remoteSocket, ProtoWriter().msg(10, ProtoWriter().int(1, keyCode).int(2, DIRECTION_SHORT)))
    }

    fun launchApp(link: String) {
        send(remoteSocket, ProtoWriter().msg(90, ProtoWriter().string(1, link)))
    }

    /** Replaces the content of the focused text field on the TV. */
    fun setText(text: String) {
        if (text.isEmpty()) return
        val pos = text.length - 1
        send(remoteSocket, ProtoWriter().msg(21, ProtoWriter()
            .int(1, imeCounter)
            .int(2, fieldCounter)
            .msg(3, ProtoWriter()
                .int(1, 1)
                .msg(2, ProtoWriter().int(1, pos).int(2, pos).string(3, text)))))
    }

    // ---- Pairing (6467) ----

    private fun runPairing(gen: Int) {
        val h = host ?: return
        try {
            val tcp = Socket()
            tcp.connect(InetSocketAddress(h, 6467), 5000)
            val s = tls(tcp, h, 6467)
            pairSocket = s
            serverCert = s.session.peerCertificates[0] as X509Certificate
            send(s, outer().msg(10, ProtoWriter().string(1, "atvremote").string(2, clientName)))

            val input = BufferedInputStream(s.inputStream)
            while (gen == generation.get()) {
                val msg = ProtoMessage.parse(readFrame(input) ?: break)
                val status = msg.int(2)
                if (status != 200L) {
                    if (status == 402L) {
                        setState(gen, State.DISCONNECTED, "Kod yanlış, tekrar deneyin")
                    } else {
                        setState(gen, State.DISCONNECTED, "Eşleştirme reddedildi ($status)")
                    }
                    break
                }
                when {
                    msg.has(11) -> send(s, outer().msg(20, ProtoWriter() // options
                        .msg(1, hexEncoding())
                        .int(3, ROLE_INPUT)))
                    msg.has(20) -> send(s, outer().msg(30, ProtoWriter() // configuration
                        .msg(1, hexEncoding())
                        .int(2, ROLE_INPUT)))
                    msg.has(31) -> setState(gen, State.NEED_PIN, null) // code is on screen now
                    msg.has(41) -> { // secret accepted
                        store.put(pairedKey(), "1")
                        closeQuietly(s)
                        pairSocket = null
                        setState(gen, State.CONNECTING, null)
                        runRemote(gen, allowPairing = false)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            setState(gen, State.DISCONNECTED, e.message ?: e.javaClass.simpleName)
        }
        closeQuietly(pairSocket)
        pairSocket = null
        if (gen == generation.get() && state != State.DISCONNECTED) {
            setState(gen, State.DISCONNECTED, "Eşleştirme kesildi")
        }
    }

    /** Sends the code shown on the TV. Returns false if it is obviously wrong. */
    fun submitPin(code: String): Boolean {
        val id = identity ?: return false
        val server = serverCert ?: return false
        val secret = AtvCrypto.secret(id.cert, server, code.trim().uppercase()) ?: return false
        send(pairSocket, outer().msg(40, ProtoWriter().bytes(1, secret)))
        return true
    }

    // ---- Plumbing ----

    private fun outer() = ProtoWriter().int(1, 2).int(2, 200)

    private fun hexEncoding() = ProtoWriter().int(1, ENCODING_HEX).int(2, 6)

    private fun send(socket: SSLSocket?, msg: ProtoWriter) {
        if (socket == null) return
        val bytes = msg.toByteArray()
        writer.execute {
            try {
                writeFrame(socket.outputStream, bytes)
            } catch (_: Exception) {
                closeQuietly(socket)
            }
        }
    }

    private fun tls(tcp: Socket, host: String, port: Int): SSLSocket {
        val id = identity ?: AtvCrypto.identity(store::get) { k, v -> store.put(k, v) }.also { identity = it }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("client", id.key, CharArray(0), arrayOf(id.cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(TrustAll), null)
        val s = ctx.socketFactory.createSocket(tcp, host, port, true) as SSLSocket
        s.startHandshake()
        return s
    }

    private fun setState(gen: Int, s: State, message: String?) {
        if (gen != generation.get()) return
        state = s
        listener.onState(s, message)
    }

    private fun closeQuietly(s: Socket?) {
        try { s?.close() } catch (_: Exception) {}
    }

    /** The TV's certificate is self-signed; pairing itself authenticates both sides. */
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    companion object {
        // PING | KEY | IME | POWER | VOLUME | APP_LINK
        const val FEATURES = 1L or 2L or 4L or 32L or 64L or 512L
        const val DIRECTION_SHORT = 3
        const val ROLE_INPUT = 1
        const val ENCODING_HEX = 3
    }
}
