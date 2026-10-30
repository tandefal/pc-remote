package dev.pcremote.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pcremote.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

data class UiState(val credentials: ComputerCredentials? = null, val computers: List<ComputerCredentials> = emptyList(), val online: Boolean = false, val loading: Boolean = false, val apps: List<RemoteApp> = emptyList(), val error: String? = null, val notice: String? = null, val lockConfirmed: Boolean = false, val page: Page = Page.HOME, val pairing: Boolean = false)
enum class Page { HOME, APPS, COMPUTERS, CONNECT, SETTINGS }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = (application as PCRemoteApplication).preferences
    private val api = ApiClient()
    private val initialComputers = prefs.loadAll()
    private val initialCredentials = prefs.load()
    private val _state = MutableStateFlow(UiState(credentials = initialCredentials, computers = initialComputers, page = if (initialCredentials == null) Page.CONNECT else Page.HOME))
    val state = _state.asStateFlow()
    private var pendingPayload: PairingPayload? = null
    private val discovered = mutableMapOf<String, DiscoveredComputer>()
    private var monitor: Job? = null
    private var pairingTimeout: Job? = null
    private var lockFeedback: Job? = null
    private val discovery = DiscoveryManager(application, ::computerFound) { error(it) }

    fun startActive() { discovery.start(); startMonitor() }
    fun stopActive() { discovery.stop(); monitor?.cancel(); monitor = null }
    fun select(page: Page) { _state.value = _state.value.copy(page = page, error = null); if (page == Page.APPS) refreshApps() }
    fun selectComputer(id: String) {
        if (_state.value.loading) return
        val computer = _state.value.computers.firstOrNull { it.id == id } ?: return
        prefs.select(id)
        _state.value = _state.value.copy(credentials = computer, online = false, apps = emptyList(), error = null, page = Page.HOME)
        startMonitor()
    }
    fun acceptQr(raw: String) {
        try {
            val j = JSONObject(raw)
            require(j.getInt("v") == 1)
            val payload = PairingPayload(j.getString("id"), j.getString("name"), j.getInt("port"), j.getString("secret"), j.getString("cert"))
            require(payload.id.matches(Regex("[a-fA-F0-9]{32}")) && payload.certificateSha256.matches(Regex("[a-fA-F0-9]{64}")) && payload.port in 1..65535 && payload.secret.length in 32..128)
            pendingPayload = payload
            _state.value = _state.value.copy(pairing = true, error = null)
            pairingTimeout?.cancel()
            pairingTimeout = viewModelScope.launch { delay(20_000); if (pendingPayload != null) { pendingPayload = null; error("Компьютер не найден. Проверьте общую сеть, Firewall и доступность mDNS, затем отсканируйте новый код.") } }
            discovery.start(); discovered[payload.id]?.let(::computerFound)
        }
        catch (_: Exception) { error("Это не QR-код PC Remote.") }
    }
    fun reportError(message: String) = error(message)
    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }
    private fun computerFound(found: DiscoveredComputer) {
        discovered[found.id] = found
        val current = _state.value.credentials
        if (current?.id == found.id && (current.host != found.host || current.port != found.port)) { val updated = current.copy(host = found.host, port = found.port); prefs.save(updated); _state.value = _state.value.copy(credentials = updated, computers = prefs.loadAll()) }
        val pending = pendingPayload ?: return
        if (pending.id != found.id || !pending.certificateSha256.equals(found.certificateSha256, true)) return
        pendingPayload = null; pairingTimeout?.cancel(); viewModelScope.launch { runTask { val credentials = api.pair(found, pending.secret, Build.MODEL); prefs.save(credentials); _state.value = _state.value.copy(credentials = credentials, computers = prefs.loadAll(), pairing = false, page = Page.HOME, online = true) } }
    }
    private fun startMonitor() { monitor?.cancel(); monitor = viewModelScope.launch { while (isActive) { val c = _state.value.credentials; if (c != null) try { withContext(Dispatchers.IO) { api.health(c); api.authCheck(c) }; if (_state.value.credentials?.id == c.id) _state.value = _state.value.copy(online = true) } catch (e: ApiException) { if (_state.value.credentials?.id == c.id) { if (e.status == 401) forgetRevoked(c) else _state.value = _state.value.copy(online = false) } } catch (_: Exception) { if (_state.value.credentials?.id == c.id) _state.value = _state.value.copy(online = false) }; delay(5_000) } } }
    private fun forgetRevoked(computer: ComputerCredentials) {
        val remaining = prefs.remove(computer.id)
        val active = prefs.load()
        _state.value = UiState(credentials = active, computers = remaining, page = if (active == null) Page.CONNECT else Page.HOME, error = "Доступ к компьютеру «${computer.name}» отозван.")
    }
    fun refreshApps() { val c = _state.value.credentials ?: return; viewModelScope.launch { runTask { val list = api.apps(c); _state.value = _state.value.copy(apps = list) } } }
    fun lock() = command("/api/v1/commands/lock", false, true)
    fun reboot() = command("/api/v1/commands/reboot", false)
    fun shutdown() = command("/api/v1/commands/shutdown", false)
    fun exit(id: String) = command("/api/v1/apps/$id/exit", true)
    fun terminate(id: String) = command("/api/v1/apps/$id/terminate", true)
    fun terminateAll() = command("/api/v1/apps/terminate-all", true)
    private fun command(path: String, refresh: Boolean, confirmsLock: Boolean = false) { val c = _state.value.credentials ?: return; viewModelScope.launch { runTask { val message = api.command(c, path); val updatedApps = if (refresh) api.apps(c) else _state.value.apps; _state.value = _state.value.copy(apps = updatedApps, notice = message, lockConfirmed = confirmsLock || _state.value.lockConfirmed); if (confirmsLock) { lockFeedback?.cancel(); lockFeedback = viewModelScope.launch { delay(8_000); _state.value = _state.value.copy(lockConfirmed = false) } } } } }
    private suspend fun runTask(block: suspend () -> Unit) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null, notice = null)
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) { if (e.status == 401) _state.value.credentials?.let(::forgetRevoked) ?: error("Авторизация отклонена.") else error(e.message) }
        catch (_: IOException) { error("Не удалось установить соединение с компьютером. Проверьте, что телефон и ПК подключены к одной домашней сети (ПК может быть подключён кабелем), PC Remote запущен, а брандмауэр Windows разрешает частную сеть.") }
        catch (e: Exception) { error(e.message ?: "Не удалось выполнить операцию.") }
        finally { _state.value = _state.value.copy(loading = false, pairing = false) }
    }
    private fun error(message: String) { _state.value = _state.value.copy(error = message, notice = null, pairing = false) }
    override fun onCleared() { discovery.stop(); monitor?.cancel(); pairingTimeout?.cancel(); lockFeedback?.cancel(); super.onCleared() }
}
