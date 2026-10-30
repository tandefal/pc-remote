package dev.pcremote.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val alias = "pc_remote_credentials"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    private fun write(computers: List<ComputerCredentials>, activeId: String?) {
        val array = JSONArray()
        computers.forEach { array.put(it.toJson()) }
        val plain = JSONObject().put("computers", array).put("activeId", activeId).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString("data", Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)).apply()
    }

    private fun JSONObject.toCredentials() = ComputerCredentials(getString("id"), getString("name"), getString("host"), getInt("port"), getString("token"), getString("cert"))
    private fun ComputerCredentials.toJson() = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port).put("token", token).put("cert", certificateSha256)

    private fun read(): Pair<List<ComputerCredentials>, String?> = try {
        val raw = Base64.decode(prefs.getString("data", null) ?: return emptyList<ComputerCredentials>() to null, Base64.NO_WRAP); val iv = raw.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val root = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size))))
        if (!root.has("computers")) {
            val legacy = listOf(root.toCredentials())
            write(legacy, legacy.first().id)
            legacy to legacy.first().id
        } else {
            val array = root.getJSONArray("computers")
            val computers = (0 until array.length()).map { array.getJSONObject(it).toCredentials() }
            computers to root.optString("activeId").takeIf { id -> computers.any { it.id == id } }
        }
    } catch (_: Exception) { clear(); emptyList<ComputerCredentials>() to null }

    fun loadAll(): List<ComputerCredentials> = read().first
    fun load(): ComputerCredentials? {
        val (computers, activeId) = read()
        return computers.firstOrNull { it.id == activeId } ?: computers.firstOrNull()
    }
    fun save(value: ComputerCredentials) {
        val (computers, _) = read()
        write(computers.filterNot { it.id == value.id } + value, value.id)
    }
    fun select(id: String) {
        val (computers, _) = read()
        if (computers.any { it.id == id }) write(computers, id)
    }
    fun remove(id: String): List<ComputerCredentials> {
        val (computers, activeId) = read()
        val remaining = computers.filterNot { it.id == id }
        if (remaining.size != computers.size) write(remaining, if (activeId == id) remaining.firstOrNull()?.id else activeId)
        return remaining
    }
    fun clear() { prefs.edit().clear().apply() }
}
