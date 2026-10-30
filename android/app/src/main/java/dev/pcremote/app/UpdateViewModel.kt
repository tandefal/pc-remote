package dev.pcremote.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppUpdate(val version: String, val notes: String, val url: String, val sha256: String, val size: Long)
data class UpdateState(val busy: Boolean = false, val message: String = "", val release: AppUpdate? = null, val apk: File? = null)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()

    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000
        setRequestProperty("User-Agent", "PCRemote/${BuildConfig.VERSION_NAME}")
    }

    fun check() {
        if (mutable.value.busy) return
        mutable.value = UpdateState(busy = true, message = "Проверка обновлений…")
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    val c = connection("https://api.github.com/repos/tandefal/pc-remote/releases/latest")
                    try {
                        if (c.responseCode == 404) return@withContext null
                        check(c.responseCode == 200)
                        val json = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                        val tag = json.getString("tag_name")
                        val parts = tag.removePrefix("v").split('.').map { it.toInt() }
                        require(parts.size == 3)
                        val current = BuildConfig.VERSION_NAME.split('.').map { it.toInt() }
                        val different = parts.zip(current).firstOrNull { it.first != it.second }
                        if (different == null || different.first < different.second) return@withContext null
                        val assets = json.getJSONArray("assets")
                        val name = "PCRemote-Android-${tag.removePrefix("v")}.apk"
                        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
                            ?: error("APK ещё не опубликован в релизе.")
                        val digest = asset.getString("digest")
                        val url = asset.getString("browser_download_url")
                        val size = asset.getLong("size")
                        require(digest.matches(Regex("sha256:[a-fA-F0-9]{64}")) && size in 1..104857600 && url.startsWith("https://github.com/tandefal/pc-remote/releases/download/"))
                        AppUpdate(tag, json.optString("body"), url, digest.removePrefix("sha256:"), size)
                    } finally { c.disconnect() }
                }
                mutable.value = UpdateState(release = release, message = if (release == null) "Установлена последняя опубликованная версия." else "Доступна версия ${release.version}")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = UpdateState(message = "Не удалось проверить обновления. Проверьте интернет и наличие APK с SHA-256 в релизе.") }
        }
    }

    fun download() {
        val release = mutable.value.release ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = "Скачивание и проверка обновления…", apk = null)
        viewModelScope.launch {
            val file = File(app.cacheDir, "updates/update.apk")
            try {
                withContext(Dispatchers.IO) {
                    file.parentFile!!.mkdirs()
                    val c = connection(release.url)
                    try {
                        check(c.responseCode == 200)
                        c.inputStream.use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(81920); var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count; require(total <= release.size)
                                output.write(buffer, 0, count)
                            }
                            require(total == release.size)
                        } }
                    } finally { c.disconnect() }
                    validate(file, release)
                }
                mutable.value = mutable.value.copy(busy = false, apk = file, message = "Обновление скачано. Нажмите «Установить» и подтвердите установку Android.")
            } catch (e: CancellationException) { file.delete(); throw e }
            catch (_: Exception) { file.delete(); mutable.value = mutable.value.copy(busy = false, message = "Не удалось скачать или проверить APK. Повторите попытку.") }
        }
    }

    @Suppress("DEPRECATION")
    private fun validate(file: File, release: AppUpdate) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(81920); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, true))
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Некорректный APK")
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName == app.packageName && archive.longVersionCode > installed.longVersionCode)
        val existing = installed.signingInfo!!.apkContentsSigners.toSet()
        require(archive.signingInfo!!.apkContentsSigners.toSet() == existing)
    }

    fun install() {
        val current = mutable.value
        val file = current.apk ?: return
        try {
            if (!app.packageManager.canRequestPackageInstalls()) {
                app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                mutable.value = current.copy(message = "Разрешите установку из этого источника, вернитесь и снова нажмите «Установить».")
                return
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { mutable.value = current.copy(message = "Не удалось открыть установщик Android. Повторите скачивание.") }
    }
}
