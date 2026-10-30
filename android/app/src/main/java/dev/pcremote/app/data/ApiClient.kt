package dev.pcremote.app.data

import android.annotation.SuppressLint
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ApiClient {
    @SuppressLint("CustomX509TrustManager")
    private fun connection(host: String, port: Int, fingerprint: String, path: String, method: String, token: String? = null): HttpsURLConnection {
        val expected = fingerprint.lowercase().replace(":", "")
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val actual = MessageDigest.getInstance("SHA-256").digest(chain.firstOrNull()?.encoded ?: throw java.security.cert.CertificateException("Сертификат отсутствует")).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) throw java.security.cert.CertificateException("Идентичность компьютера не подтверждена")
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return (URL("https", host, port, path).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = context.socketFactory; hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            requestMethod = method; connectTimeout = 4_000; readTimeout = 8_000; setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
    }
    private fun read(connection: HttpURLConnection): String {
        val code = connection.responseCode; val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) { val message = runCatching { JSONObject(text).optString("message") }.getOrNull(); throw ApiException(code, message?.takeIf { it.isNotBlank() } ?: "Ошибка компьютера ($code).") }
        return text
    }
    fun pair(computer: DiscoveredComputer, secret: String, deviceName: String): ComputerCredentials {
        val c = connection(computer.host, computer.port, computer.certificateSha256, "/api/v1/pair", "POST").apply { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(JSONObject().put("secret", secret).put("deviceName", deviceName).toString().toByteArray()) } }
        val json = JSONObject(read(c)); return ComputerCredentials(json.getString("computerId"), json.getString("computerName"), computer.host, computer.port, json.getString("token"), json.getString("certificateSha256"))
    }
    fun health(credentials: ComputerCredentials): String { val json = JSONObject(read(connection(credentials.host, credentials.port, credentials.certificateSha256, "/api/v1/health", "GET"))); return json.getString("computerName") }
    fun authCheck(credentials: ComputerCredentials) { read(connection(credentials.host, credentials.port, credentials.certificateSha256, "/api/v1/auth/check", "GET", credentials.token)) }
    fun apps(credentials: ComputerCredentials): List<RemoteApp> { val a = JSONArray(read(connection(credentials.host, credentials.port, credentials.certificateSha256, "/api/v1/apps", "GET", credentials.token))); return (0 until a.length()).map { a.getJSONObject(it).run { RemoteApp(getString("id"), getString("name"), optString("iconPngBase64").takeIf { s -> s.isNotBlank() && s != "null" }, getInt("windowCount")) } } }
    fun command(credentials: ComputerCredentials, path: String): String { val c = connection(credentials.host, credentials.port, credentials.certificateSha256, path, "POST", credentials.token).apply { doOutput = true; setRequestProperty("X-Request-Id", java.util.UUID.randomUUID().toString()); outputStream.use { } }; return JSONObject(read(c)).optString("message").ifBlank { "Операция выполнена." } }
}

class ApiException(val status: Int, override val message: String) : Exception(message)
