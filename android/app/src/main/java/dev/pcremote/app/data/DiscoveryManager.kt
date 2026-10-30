package dev.pcremote.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

class DiscoveryManager(context: Context, private val onComputer: (DiscoveredComputer) -> Unit, private val onError: (String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val multicastLock = context.getSystemService(WifiManager::class.java).createMulticastLock("PCRemote.mDNS").apply { setReferenceCounted(false) }
    private var running = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { running = true }
        override fun onServiceFound(info: NsdServiceInfo) { if (info.serviceType.startsWith("_pcremote._tcp")) nsd.resolveService(info, resolveListener) }
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(type: String) { running = false }
        override fun onStartDiscoveryFailed(type: String, code: Int) { running = false; if (multicastLock.isHeld) multicastLock.release(); onError("Не удалось начать поиск компьютера ($code).") }
        override fun onStopDiscoveryFailed(type: String, code: Int) { running = false }
    }
    @Suppress("DEPRECATION")
    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
        override fun onServiceResolved(info: NsdServiceInfo) {
            val attrs = info.attributes.mapValues { String(it.value, Charsets.UTF_8) }
            val id = attrs["id"] ?: return; val cert = attrs["cert"] ?: return
            onComputer(DiscoveredComputer(id, attrs["name"] ?: "Компьютер", info.host.hostAddress ?: return, info.port, cert))
        }
    }
    fun start() { if (!running) try { running = true; if (!multicastLock.isHeld) multicastLock.acquire(); nsd.discoverServices("_pcremote._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) } catch (e: Exception) { running = false; if (multicastLock.isHeld) multicastLock.release(); onError(e.message ?: "Ошибка поиска компьютера.") } }
    fun stop() {
        if (running) {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
            finally { running = false; if (multicastLock.isHeld) multicastLock.release() }
        } else if (multicastLock.isHeld) multicastLock.release()
    }
}
