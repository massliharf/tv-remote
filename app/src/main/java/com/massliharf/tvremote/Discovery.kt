package com.massliharf.tvremote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds LG webOS TVs on the local network with an SSDP M-SEARCH. */
object Discovery {

    data class Tv(val host: String, val name: String)

    private const val SEARCH =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"

    fun search(context: Context, timeoutMs: Int = 3000, done: (List<Tv>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Thread {
            val lock = wifi.createMulticastLock("tv-remote-ssdp").apply { setReferenceCounted(false) }
            val found = LinkedHashMap<String, Tv>()
            try {
                lock.acquire()
                DatagramSocket().use { sock ->
                    sock.soTimeout = 500
                    val data = SEARCH.toByteArray()
                    val group = InetAddress.getByName("239.255.255.250")
                    repeat(2) { sock.send(DatagramPacket(data, data.size, group, 1900)) }
                    val buf = ByteArray(2048)
                    val end = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < end) {
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(pkt)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val text = String(pkt.data, 0, pkt.length)
                        if (!text.contains("webos-second-screen", ignoreCase = true) &&
                            !text.contains("LGE", ignoreCase = true)
                        ) continue
                        val host = pkt.address.hostAddress ?: continue
                        found[host] = Tv(host, "LG TV")
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (lock.isHeld) lock.release()
            }
            main.post { done(found.values.toList()) }
        }.start()
    }
}
