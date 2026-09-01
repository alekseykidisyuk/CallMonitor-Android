/*
 * CallMonitor Android — compatibility transport for OEMs that advertise Wireless Debugging
 * on the device Wi-Fi address but do not accept the same connection on 127.0.0.1.
 *
 * Derived from the CallVault GPLv3 codebase. See LICENSE at repository root.
 */

package com.baba.callvault.integrations.adb

import android.util.Log
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes an mDNS-advertised ADB endpoint reachable at 127.0.0.1:<port>.
 *
 * Some OEM ROMs (confirmed on Xiaomi/HyperOS) advertise `_adb-tls-*` on the phone's WLAN IPv4
 * address, while libadb callers in CallVault connect to 127.0.0.1. Pairing then discovers the correct
 * port but the TCP connection is refused locally. A tiny process-local TCP bridge fixes the transport
 * without changing ADB framing/TLS/authentication: bytes are forwarded unchanged to the phone's own
 * Wi-Fi address.
 *
 * On ROMs where 127.0.0.1 already works, no proxy is created. Proxies bind LOOPBACK ONLY and live for
 * the app process lifetime; mDNS ports are ephemeral, so keeping an old local-only bridge is harmless.
 */
object AdbLoopbackProxy {
    private const val TAG = "CV:AdbLoopbackProxy"
    private const val PROBE_TIMEOUT_MS = 250
    private const val TARGET_CONNECT_TIMEOUT_MS = 3_000

    private val proxies = ConcurrentHashMap<Int, ProxyServer>()

    /**
     * Ensures callers can reach [targetHost]:[port] through 127.0.0.1:[port].
     * Returns true when loopback already works or a bridge was started successfully.
     */
    fun ensure(targetHost: String, port: Int): Boolean {
        if (port <= 0 || targetHost.isBlank()) return false

        if (loopbackAccepts(port)) {
            Log.d(TAG, "127.0.0.1:$port already reachable; proxy not needed")
            return true
        }

        proxies[port]?.let { existing ->
            if (existing.targetHost == targetHost && existing.running) return true
            existing.close()
            proxies.remove(port, existing)
        }

        return runCatching {
            ProxyServer(targetHost, port).also { server ->
                server.start()
                proxies[port] = server
            }
            Log.i(TAG, "ADB loopback bridge active: 127.0.0.1:$port -> $targetHost:$port")
            true
        }.getOrElse { e ->
            // A race is possible: adbd may have started listening on loopback between our probe and bind.
            // If so, treat a now-live loopback endpoint as success.
            val liveNow = loopbackAccepts(port)
            if (liveNow) {
                Log.i(TAG, "Loopback became reachable while creating proxy on :$port")
                true
            } else {
                Log.w(TAG, "Could not create ADB loopback bridge for $targetHost:$port: ${e.message}")
                false
            }
        }
    }

    private fun loopbackAccepts(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private class ProxyServer(
        val targetHost: String,
        private val port: Int,
    ) : Closeable {
        private lateinit var serverSocket: ServerSocket

        @Volatile
        var running: Boolean = false
            private set

        fun start() {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
            serverSocket = ss
            running = true

            Thread({ acceptLoop() }, "cv-adb-proxy-$port").apply {
                isDaemon = true
                start()
            }
        }

        private fun acceptLoop() {
            while (running) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    if (!running) return
                    continue
                }

                Thread({ bridge(client) }, "cv-adb-proxy-conn-$port").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun bridge(client: Socket) {
            client.use { local ->
                val remote = Socket()
                try {
                    remote.connect(InetSocketAddress(targetHost, port), TARGET_CONNECT_TIMEOUT_MS)
                    remote.tcpNoDelay = true
                    local.tcpNoDelay = true

                    val upstream = Thread({
                        runCatching {
                            local.getInputStream().copyTo(remote.getOutputStream())
                            runCatching { remote.shutdownOutput() }
                        }
                    }, "cv-adb-proxy-up-$port").apply { isDaemon = true }

                    val downstream = Thread({
                        runCatching {
                            remote.getInputStream().copyTo(local.getOutputStream())
                            runCatching { local.shutdownOutput() }
                        }
                    }, "cv-adb-proxy-down-$port").apply { isDaemon = true }

                    upstream.start()
                    downstream.start()
                    upstream.join()
                    downstream.join()
                } catch (e: Exception) {
                    Log.d(TAG, "Proxy connection to $targetHost:$port ended: ${e.message}")
                } finally {
                    runCatching { remote.close() }
                }
            }
        }

        override fun close() {
            running = false
            if (::serverSocket.isInitialized) runCatching { serverSocket.close() }
        }
    }
}
