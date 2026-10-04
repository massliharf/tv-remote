package com.massliharf.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds LG webOS TVs (SSDP) and Android TV boxes (mDNS) on the local network. */
object Discovery {

    private const val SEARCH =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"

    /** Runs both searches for [timeoutMs] and reports every device found, on the main thread. */
    fun search(context: Context, timeoutMs: Long = 4000, done: (List<Device>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val app = context.applicationContext
        val found = LinkedHashMap<String, Device>()
        var pending = 2
        fun finish(list: List<Device>) {
            main.post {
                list.forEach { found[it.id] = it }
                if (--pending == 0) done(found.values.toList())
            }
        }
        searchLg(app, timeoutMs.toInt(), ::finish)
        searchAndroidTv(app, timeoutMs, ::finish)
    }

    private fun searchLg(context: Context, timeoutMs: Int, done: (List<Device>) -> Unit) {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Thread {
            val lock = wifi.createMulticastLock("tv-remote-ssdp").apply { setReferenceCounted(false) }
            val found = LinkedHashMap<String, Device>()
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
                        found[host] = Device(Device.Type.LG, host, "LG TV")
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (lock.isHeld) lock.release()
            }
            done(found.values.toList())
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun searchAndroidTv(context: Context, timeoutMs: Long, done: (List<Device>) -> Unit) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            done(emptyList())
            return
        }
        val main = Handler(Looper.getMainLooper())
        val found = LinkedHashMap<String, Device>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var finished = false

        // Older Android versions can only resolve one service at a time.
        fun resolveNext() {
            if (resolving || finished) return
            val info = queue.removeFirstOrNull() ?: return
            resolving = true
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        serviceInfo.host?.hostAddress?.let { host ->
                            found[host] = Device(Device.Type.ANDROID_TV, host, serviceInfo.serviceName)
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post { queue.addLast(serviceInfo); resolveNext() }
            }
        }
        try {
            nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            done(emptyList())
            return
        }
        main.postDelayed({
            finished = true
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            done(found.values.toList())
        }, timeoutMs)
    }
}
