/*
 * AdbMdns — mDNS discovery for ADB wireless-debugging services.
 *
 * Ported from RikkaApps/Shizuku (Apache-2.0):
 *   manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt
 * Adapted: LiveData Observer<Int> → a plain (Int) -> Unit callback, and namespaced
 * into the CallVault production integrations package.
 *
 * CallMonitor addition: some OEMs (confirmed on Xiaomi/HyperOS) advertise ADB on the
 * phone's WLAN address but reject the equivalent 127.0.0.1 connection. Discovery therefore
 * prepares [AdbLoopbackProxy] before reporting the port, so the rest of the embedded-ADB stack
 * can keep using localhost unchanged.
 *
 * KEY POINT vs libadb-android's bundled AdbMdns: the service type passed to
 * NsdManager is the FULL form `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`
 * (with leading underscore and `._tcp`). libadb's constants are the bare
 * `adb-tls-pairing`, which NsdManager never matches — that is why discovery found
 * nothing in the first spike iteration.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.NetworkInterface

/**
 * Discovers an ADB mDNS service of [serviceType] and reports the resolved port via
 * [onPort]. A port of `-1` means the previously-found service was lost.
 *
 * Call [start] to begin discovery and [stop] when done. Discovery should be running
 * BEFORE the pairing service appears (i.e. before/while the user opens the phone's
 * "Pair device with pairing code" dialog), which is why callers keep it alive in a
 * service rather than firing it once on demand.
 */
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val onPort: (Int) -> Unit,
) {
    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) onPort(-1)
    }

    @Suppress("DEPRECATION")
    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        if (!running) return

        val hostAddress = resolvedService.host?.hostAddress ?: return
        if (!isLocalAddress(hostAddress)) {
            Log.d(TAG, "Ignoring ADB mDNS service on non-local host $hostAddress")
            return
        }

        // Stock CallVault assumes the discovered ADB service is also reachable at 127.0.0.1:port.
        // Xiaomi/HyperOS disproves that assumption: mDNS resolves to the phone WLAN address and
        // localhost refuses the same port. Prepare a transparent loopback bridge when needed.
        if (!AdbLoopbackProxy.ensure(hostAddress, resolvedService.port)) {
            Log.w(TAG, "Resolved ADB service $hostAddress:${resolvedService.port}, but loopback bridge failed")
            return
        }

        serviceName = resolvedService.serviceName
        onPort(resolvedService.port)
    }

    private fun isLocalAddress(hostAddress: String): Boolean =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { it.hostAddress == hostAddress }
                }
        }.getOrDefault(false)

    private class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = adbMdns.onDiscoveryStart()
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStartDiscoveryFailed: $serviceType err=$errorCode")
        }
        override fun onDiscoveryStopped(serviceType: String) = adbMdns.onDiscoveryStop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStopDiscoveryFailed: $serviceType err=$errorCode")
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) = adbMdns.onServiceFound(serviceInfo)
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = adbMdns.onServiceLost(serviceInfo)
    }

    @Suppress("DEPRECATION")
    private class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}
        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) =
            adbMdns.onServiceResolved(nsdServiceInfo)
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        private const val TAG = "AdbMdns"

        /**
         * Blocks (up to [timeoutMs]) until an mDNS service of [serviceType] is found,
         * returning its port or null on timeout. Must be called off the main thread.
         */
        fun discoverPort(context: Context, serviceType: String, timeoutMs: Long): Int? {
            val portRef = java.util.concurrent.atomic.AtomicInteger(-1)
            val latch = java.util.concurrent.CountDownLatch(1)
            val mdns = AdbMdns(context, serviceType) { port ->
                if (port > 0) {
                    portRef.set(port)
                    latch.countDown()
                }
            }
            mdns.start()
            return try {
                if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) null
                else portRef.get().takeIf { it > 0 }
            } finally {
                mdns.stop()
            }
        }
    }
}
