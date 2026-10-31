package dev.pcremote.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

class DiscoveryManager(context: Context, private val onComputer: (DiscoveredComputer) -> Unit, private val onError: (String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val multicastLock = context.getSystemService(WifiManager::class.java).createMulticastLock("PCRemote.mDNS").apply { setReferenceCounted(false) }
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = linkedMapOf<String, NsdServiceInfo>()
    private val present = mutableSetOf<String>()
    private var resolving = false
    private fun release() { if (multicastLock.isHeld) multicastLock.release() }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || listener == null || pending.isEmpty()) return
        val info = pending.entries.first().value
        pending.remove(info.serviceName)
        val owner = listener
        resolving = true
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { handler.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(info: NsdServiceInfo) { handler.post {
                resolving = false
                if (listener === owner && info.serviceName in present) {
                    val attrs = info.attributes.mapValues { String(it.value, Charsets.UTF_8) }
                    val id = attrs["id"]; val cert = attrs["cert"]; val host = info.host?.hostAddress
                    if (id != null && cert != null && host != null && info.port in 1..65535)
                        onComputer(DiscoveredComputer(id, attrs["name"] ?: "Компьютер", host, info.port, cert))
                }
                resolveNext()
            } }
        }
        try { nsd.resolveService(info, resolver) }
        catch (_: Exception) { resolving = false; resolveNext() }
    }
    fun start() {
        if (listener != null) return
        val current = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { handler.post {
                if (listener === this && info.serviceType.startsWith("_pcremote._tcp")) {
                    if (pending.size >= 64 && info.serviceName !in pending) return@post
                    present.add(info.serviceName); pending[info.serviceName] = info; resolveNext()
                }
            } }
            override fun onServiceLost(info: NsdServiceInfo) { handler.post {
                if (listener === this) { present.remove(info.serviceName); pending.remove(info.serviceName) }
            } }
            override fun onDiscoveryStopped(type: String) { handler.post { if (listener === this) { listener = null; pending.clear(); present.clear(); release() } } }
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post {
                if (listener === this) { stop(); onError("Не удалось начать поиск компьютера ($code).") }
            } }
            override fun onStopDiscoveryFailed(type: String, code: Int) { handler.post { if (listener === this) stop() } }
        }
        listener = current
        if (networkCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { handler.post {
                    if (networkCallback === this && listener != null) {
                        val saved = networkCallback
                        networkCallback = null
                        stop()
                        networkCallback = saved
                        start()
                    }
                } }
            }
            networkCallback = callback
            try { connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback) }
            catch (_: Exception) { networkCallback = null }
        }
        try { multicastLock.acquire(); nsd.discoverServices("_pcremote._tcp.", NsdManager.PROTOCOL_DNS_SD, current) }
        catch (_: Exception) { stop(); onError("Ошибка поиска компьютера.") }
    }
    fun stop() {
        networkCallback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        networkCallback = null
        val current = listener
        listener = null; pending.clear(); present.clear(); release()
        if (current != null) try { nsd.stopServiceDiscovery(current) } catch (_: Exception) { }
    }
}
