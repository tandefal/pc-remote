package dev.pcremote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.pcremote.app.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegrationTest {
    @Test fun httpsPairingKeystoreAndUi() {
        val args = InstrumentationRegistry.getArguments()
        val computer = DiscoveredComputer(args.getString("id")!!, "Проверка PC Remote", "10.0.2.2", args.getString("port")!!.toInt(), args.getString("cert")!!)
        val api = ApiClient()
        val credentials = api.pair(computer, args.getString("secret")!!, "Android emulator test")
        assertEquals(computer.id, credentials.id)
        assertEquals(computer.name, api.health(credentials))
        api.authCheck(credentials)
        api.apps(credentials)
        try { api.authCheck(credentials.copy(token = "invalid")); fail("Invalid token accepted") } catch (e: ApiException) { assertEquals(401, e.status) }
        try { api.health(credentials.copy(certificateSha256 = "00".repeat(32))); fail("Invalid TLS pin accepted") } catch (_: javax.net.ssl.SSLException) { }
        try { api.pair(computer, args.getString("secret")!!, "Replay"); fail("Pairing replay accepted") } catch (e: ApiException) { assertEquals(401, e.status) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = SecurePreferences(context)
        try {
            prefs.save(credentials)
            assertEquals(credentials, SecurePreferences(context).load())
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { assertFalse(it.isFinishing) } }
        } finally { prefs.clear() }
    }
}
