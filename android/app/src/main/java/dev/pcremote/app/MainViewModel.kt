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

data class UiState(val monitoring: SystemMetrics? = null, val history: List<MonitoringPoint> = emptyList(), val credentials: ComputerCredentials? = null, val computers: List<ComputerCredentials> = emptyList(), val online: Boolean = false, val connecting: Boolean = false, val loading: Boolean = false, val apps: List<RemoteApp> = emptyList(), val error: String? = null, val notice: String? = null, val locked: Boolean? = null, val page: Page = Page.HOME, val pairing: Boolean = false)
enum class Page { HOME, SYSTEM, APPS, COMPUTERS, CONNECT, SETTINGS }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = (application as PCRemoteApplication).preferences
    private val api = ApiClient()
    private val initialRead = runCatching { prefs.loadAll() to prefs.load() }
    private val initialComputers = initialRead.getOrNull()?.first.orEmpty()
    private val initialCredentials = initialRead.getOrNull()?.second
    private val _state = MutableStateFlow(UiState(connecting = initialCredentials != null, credentials = initialCredentials, computers = initialComputers, error = initialRead.exceptionOrNull()?.message, page = if (initialCredentials == null) Page.CONNECT else Page.HOME))
    val state = _state.asStateFlow()
    private var pendingPayload: PairingPayload? = null
    private val discovered = mutableMapOf<String, DiscoveredComputer>()
    private var monitor: Job? = null
    private var pairingTimeout: Job? = null
    private var active = false
    private var lastMetricsAt = 0L
    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (_state.value.monitoring != null && android.os.SystemClock.elapsedRealtime() - lastMetricsAt > 6000)
                    _state.value = _state.value.copy(monitoring = null, history = emptyList())
            }
        }
    }
    private val discovery = DiscoveryManager(application, ::computerFound) { error(it) }

    fun startActive() { if (active) return; active = true; discovery.start(); startMonitor() }
    fun stopActive() { active = false; discovery.stop(); monitor?.cancel(); monitor = null; _state.value = _state.value.copy(online = false, connecting = _state.value.credentials != null, locked = null, apps = emptyList(), monitoring = null, history = emptyList()) }
    fun select(page: Page) { _state.value = _state.value.copy(page = page, error = null) }
    fun selectComputer(id: String) {
        if (_state.value.loading || _state.value.pairing || _state.value.credentials?.id == id) return
        val computer = _state.value.computers.firstOrNull { it.id == id } ?: return
        prefs.select(id)
        _state.value = _state.value.copy(credentials = computer, online = false, apps = emptyList(), monitoring = null, history = emptyList(), error = null, page = Page.HOME)
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
        viewModelScope.launch {
            try { handleComputerFound(found) }
            catch (e: Exception) { error(e.message ?: "Не удалось сохранить адрес компьютера.") }
        }
    }
    private fun handleComputerFound(found: DiscoveredComputer) {
        discovered[found.id] = found
        val current = _state.value.credentials
        if (current?.id == found.id && (current.host != found.host || current.port != found.port)) { val updated = current.copy(host = found.host, port = found.port); prefs.save(updated); _state.value = _state.value.copy(credentials = updated, computers = prefs.loadAll()); startMonitor() }
        val pending = pendingPayload ?: return
        if (pending.id != found.id || !pending.certificateSha256.equals(found.certificateSha256, true)) return
        pendingPayload = null; pairingTimeout?.cancel(); viewModelScope.launch { runTask { val credentials = withContext(Dispatchers.IO) { api.pair(found, pending.secret, Build.MODEL) }; prefs.save(credentials); _state.value = _state.value.copy(credentials = credentials, computers = prefs.loadAll(), pairing = false, page = Page.HOME, online = false) }; startMonitor() }
    }
    private fun startMonitor() {
        monitor?.cancel()
        if (!active) return
        _state.value = _state.value.copy(online = false, connecting = _state.value.credentials != null, locked = null, apps = emptyList(), monitoring = null, history = emptyList())
        monitor = viewModelScope.launch {
            var retry = 1000L
            while (isActive) {
                val c = _state.value.credentials
                if (c == null) { delay(1000); continue }
                try {
                    val owner = coroutineContext[Job]!!
                    api.events(c) { data ->
                        viewModelScope.launch update@ {
                            if (!owner.isActive || _state.value.credentials != c) return@update
                            val a = data.getJSONArray("apps")
                            val list = (0 until a.length()).map { a.getJSONObject(it).run {
                                RemoteApp(getString("id"), getString("name"), optString("iconPngBase64").takeIf { v -> v.isNotBlank() && v != "null" }, getInt("windowCount"))
                            } }
                            val metrics = data.optJSONObject("monitoring")?.let { runCatching { parseMonitoring(it) }.getOrNull() }
                            val now = android.os.SystemClock.elapsedRealtime()
                            lastMetricsAt = now
                            val history = if (metrics == null) emptyList() else (_state.value.history.filter { now - it.time <= 60_000 } + MonitoringPoint(now, metrics)).takeLast(61)
                            _state.value = _state.value.copy(monitoring = metrics, history = history, online = true, connecting = false, locked = if (data.isNull("locked")) null else data.getBoolean("locked"), apps = list)
                            retry = 1000L
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: ApiException) { if (e.status == 401 && _state.value.credentials == c) forgetRevoked(c) }
                catch (_: Exception) { }
                if (_state.value.credentials == c) _state.value = _state.value.copy(online = false, connecting = false, locked = null, apps = emptyList(), monitoring = null, history = emptyList())
                delay(retry)
                retry = (retry * 2).coerceAtMost(10000L)
            }
        }
    }
    private fun forgetRevoked(computer: ComputerCredentials) {
        val remaining = prefs.remove(computer.id)
        val active = prefs.load()
        _state.value = UiState(credentials = active, computers = remaining, page = if (active == null) Page.CONNECT else Page.HOME, error = "Доступ к компьютеру «${computer.name}» отозван.")
    }
    fun removeComputer(id: String) {
        if (_state.value.loading || _state.value.pairing) return
        try {
            val selected = _state.value.credentials?.id == id
            val remaining = prefs.remove(id)
            val current = prefs.load()
            _state.value = _state.value.copy(computers = remaining, credentials = current, error = null,
                notice = "Сопряжение удалено с телефона.",
                page = if (current == null) Page.CONNECT else _state.value.page)
            if (selected) startMonitor()
        } catch (e: Exception) { error(e.message ?: "Не удалось удалить сопряжение.") }
    }
    fun lock() { if (_state.value.locked == false) command("/api/v1/commands/lock") }
    fun reboot() = command("/api/v1/commands/reboot")
    fun shutdown() = command("/api/v1/commands/shutdown")
    fun terminate(id: String) = command("/api/v1/apps/$id/terminate")
    private fun command(path: String) {
        if (!_state.value.online) return
        val c = _state.value.credentials ?: return
        viewModelScope.launch { runTask {
            val message = withContext(Dispatchers.IO) { api.command(c, path) }
            if (_state.value.credentials == c) _state.value = _state.value.copy(notice = message)
        } }
    }
    private suspend fun runTask(block: suspend () -> Unit) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null, notice = null)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) { if (e.status == 401) _state.value.credentials?.let(::forgetRevoked) ?: error("Авторизация отклонена.") else error(e.message) }
        catch (_: IOException) { error("Не удалось установить соединение с компьютером. Проверьте, что телефон и ПК подключены к одной домашней сети (ПК может быть подключён кабелем), PC Remote запущен, а брандмауэр Windows разрешает частную сеть.") }
        catch (e: Exception) { error(e.message ?: "Не удалось выполнить операцию.") }
        finally { _state.value = _state.value.copy(loading = false, pairing = false) }
    }
    private fun error(message: String) { _state.value = _state.value.copy(error = message, notice = null, pairing = false) }
    override fun onCleared() { discovery.stop(); monitor?.cancel(); pairingTimeout?.cancel();  super.onCleared() }
}
