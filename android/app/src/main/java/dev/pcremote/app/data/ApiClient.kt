package dev.pcremote.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ApiClient(context: Context? = null) {
    private val connectivity = context?.applicationContext?.getSystemService(ConnectivityManager::class.java)
    private data class NetworkState(val default: Network?, val wifi: List<Network>)
    private data class Route(val network: Network?, val state: NetworkState)
    private val routes = ConcurrentHashMap<DiscoveredComputer, Route>()
    private fun ComputerCredentials.computer() = DiscoveredComputer(id, name, host, port, certificateSha256)
    private fun networks(): NetworkState {
        val manager = connectivity ?: return NetworkState(null, emptyList())
        return NetworkState(manager.activeNetwork, manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        })
    }
    private fun selectRoute(computer: DiscoveredComputer, refresh: Boolean = false): Route {
        val state = networks()
        routes[computer]?.takeIf { !refresh && it.state == state }?.let { return it }
        routes.remove(computer)
        val failures = mutableListOf<String>()
        for (network in listOf<Network?>(null) + state.wifi) {
            val route = Route(network, state)
            try {
                val json = JSONObject(read(open(computer, route, "/api/v1/health", "GET")))
                if (json.getString("computerId") != computer.id) throw ComputerIdentityException()
                if (routes.size >= 16) routes.clear()
                routes[computer] = route
                return route
            } catch (e: Exception) {
                failures += "${routeName(route)}: ${failureReason(e)}"
            }
        }
        if (state.wifi.isEmpty()) failures += "Прямой Wi-Fi: доступная сеть не найдена"
        throw LocalConnectionException("Не удалось подключиться к ПК. ${failures.joinToString("; ")}. Проверьте доступ к домашней сети в VPN и брандмауэр Windows.")
    }
    private fun routeName(route: Route) = if (route.network == null) "Системный маршрут" else "Прямой Wi-Fi"
    private fun failureReason(error: Exception): String = when (error) {
        is ComputerIdentityException -> "идентификатор ПК не совпадает"
        is javax.net.ssl.SSLException -> "ошибка проверки сертификата или TLS-соединения"
        is java.net.SocketTimeoutException -> "ПК не ответил вовремя"
        is java.net.ConnectException -> "соединение отклонено или недоступно"
        is java.net.NoRouteToHostException -> "нет маршрута к ПК"
        is java.net.UnknownHostException -> "адрес ПК недоступен"
        is SecurityException -> "Android запретил доступ к сети"
        is ApiException -> "ПК вернул ошибку HTTP ${error.status}"
        is org.json.JSONException -> "ПК вернул некорректный ответ"
        else -> "соединение прервано или недоступно"
    }
    private fun connection(computer: DiscoveredComputer, path: String, method: String, token: String? = null, refresh: Boolean = false): HttpsURLConnection =
        open(computer, selectRoute(computer, refresh), path, method, token)

    suspend fun events(credentials: ComputerCredentials, receive: (JSONObject) -> Unit): Unit = kotlinx.coroutines.withContext<Unit>(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
        val computer = credentials.computer()
        val c = connection(computer, "/api/v1/events", "GET", credentials.token, refresh = true).apply {
            readTimeout = 12_000
            setRequestProperty("Accept", "text/event-stream")
        }
        continuation.invokeOnCancellation { c.disconnect() }
        Thread({
            try {
                if (!continuation.isActive) return@Thread
                if (c.responseCode != 200) throw ApiException(c.responseCode, "Канал состояния недоступен.")
                c.inputStream.bufferedReader().use { reader ->
                    var revoked = false
                    while (continuation.isActive) {
                        val line = reader.readLine() ?: throw java.io.EOFException()
                        if (line == "event: revoked") revoked = true
                        if (line.startsWith("data: ")) {
                            if (revoked) throw ApiException(401, "Доступ отозван.")
                            val data = JSONObject(line.substring(6))
                            if (data.getString("computerId") != credentials.id) throw java.io.IOException("Другой компьютер")
                            receive(data)
                        }
                    }
                }
            } catch (e: Exception) {
                routes.remove(computer)
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            } finally { c.disconnect() }
        }, "PCRemote-events").apply { isDaemon = true; start() }
    } }
    private val factories = object : LinkedHashMap<String, javax.net.ssl.SSLSocketFactory>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, javax.net.ssl.SSLSocketFactory>?) = size > 16
    }
    @Synchronized
    @SuppressLint("CustomX509TrustManager")
    private fun socketFactory(expected: String): javax.net.ssl.SSLSocketFactory {
        factories[expected]?.let { return it }
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val actual = MessageDigest.getInstance("SHA-256").digest(chain.firstOrNull()?.encoded ?: throw java.security.cert.CertificateException("Сертификат отсутствует")).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) throw java.security.cert.CertificateException("Идентичность компьютера не подтверждена")
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return context.socketFactory.also { factories[expected] = it }
    }
    private fun open(computer: DiscoveredComputer, route: Route, path: String, method: String, token: String? = null): HttpsURLConnection {
        val factory = socketFactory(computer.certificateSha256.lowercase().replace(":", ""))
        val url = URL("https", computer.host, computer.port, path)
        return ((route.network?.openConnection(url, java.net.Proxy.NO_PROXY) ?: url.openConnection(java.net.Proxy.NO_PROXY)) as HttpsURLConnection).apply {
            instanceFollowRedirects = false; sslSocketFactory = factory; hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            requestMethod = method; connectTimeout = 4_000; readTimeout = 8_000; setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
    }
    private fun read(connection: HttpURLConnection): String {
        try {
            val code = connection.responseCode; val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) { val message = runCatching { JSONObject(text).optString("message") }.getOrNull(); throw ApiException(code, message?.takeIf { it.isNotBlank() } ?: "Ошибка компьютера ($code).") }
            return text
        } finally { connection.disconnect() }
    }
    fun pair(computer: DiscoveredComputer, secret: String, deviceName: String): ComputerCredentials {
        val c = connection(computer, "/api/v1/pair", "POST").apply { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        try {
            c.outputStream.use { it.write(JSONObject().put("secret", secret).put("deviceName", deviceName).toString().toByteArray()) }
            val json = JSONObject(read(c))
            require(json.getString("computerId") == computer.id && json.getString("certificateSha256").equals(computer.certificateSha256, true)) { "Идентичность компьютера не подтверждена" }
            return ComputerCredentials(json.getString("computerId"), json.getString("computerName"), computer.host, computer.port, json.getString("token"), json.getString("certificateSha256"))
        } finally { c.disconnect() }
    }
    fun probe(computer: DiscoveredComputer) {
        selectRoute(computer, refresh = true)
    }
    fun health(credentials: ComputerCredentials): String { val json = JSONObject(read(connection(credentials.computer(), "/api/v1/health", "GET"))); return json.getString("computerName") }
    fun authCheck(credentials: ComputerCredentials) { read(connection(credentials.computer(), "/api/v1/auth/check", "GET", credentials.token)) }
    fun apps(credentials: ComputerCredentials): List<RemoteApp> { val a = JSONArray(read(connection(credentials.computer(), "/api/v1/apps", "GET", credentials.token))); return (0 until a.length()).map { a.getJSONObject(it).run { RemoteApp(getString("id"), getString("name"), optString("iconPngBase64").takeIf { s -> s.isNotBlank() && s != "null" }, getInt("windowCount")) } } }
    fun command(credentials: ComputerCredentials, path: String): String {
        val computer = credentials.computer()
        try {
            val c = connection(computer, path, "POST", credentials.token).apply { doOutput = true; setRequestProperty("X-Request-Id", java.util.UUID.randomUUID().toString()) }
            try {
                c.outputStream.use { }
                return JSONObject(read(c)).optString("message").ifBlank { "Операция выполнена." }
            } finally { c.disconnect() }
        } catch (e: IOException) {
            routes.remove(computer)
            if (e is LocalConnectionException) throw e
            throw LocalConnectionException("Связь с ПК прервана: ${failureReason(e)}. Команда автоматически не повторялась.")
        }
    }
}

private class ComputerIdentityException : IOException()
class LocalConnectionException(message: String) : IOException(message)
class ApiException(val status: Int, override val message: String) : Exception(message)
