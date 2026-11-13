package dev.pcremote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.pcremote.app.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.launch

@RunWith(AndroidJUnit4::class)
class IntegrationTest {
    @Test fun httpsPairingKeystoreAndUi() {
        val args = InstrumentationRegistry.getArguments()
        val computer = DiscoveredComputer(args.getString("id")!!, "Проверка PC Remote", "10.0.2.2", args.getString("port")!!.toInt(), args.getString("cert")!!)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = ApiClient(context)
        api.probe(computer)
        try { api.probe(computer.copy(id = "wrong-computer")); fail("Wrong computer identity accepted") } catch (_: java.io.IOException) { }
        try { api.probe(computer.copy(certificateSha256 = "00".repeat(32))); fail("Invalid TLS pin accepted") } catch (_: javax.net.ssl.SSLException) { }
        val credentials = api.pair(computer, args.getString("secret")!!, "Android emulator test")
        assertEquals(computer.id, credentials.id)
        assertEquals(computer.name, api.health(credentials))
        api.authCheck(credentials)
        api.apps(credentials)
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(15_000) {
                val received = kotlinx.coroutines.CompletableDeferred<Unit>()
                val stream = launch {
                    api.events(credentials) { snapshot ->
                        assertEquals(credentials.id, snapshot.getString("computerId"))
                        assertNotNull(snapshot.getJSONArray("apps"))
                        val metrics = parseMonitoring(snapshot.getJSONObject("monitoring"))
                        assertTrue(metrics.cpu.name.isNotBlank())
                        assertNotNull(metrics.memory)
                        received.complete(Unit)
                    }
                }
                try { received.await() } finally { stream.cancel(); stream.join() }
            }
            try {
                kotlinx.coroutines.withTimeout(10_000) { api.events(credentials.copy(token = "invalid")) { fail("Unauthorized stream") } }
                fail("Invalid stream token accepted")
            } catch (e: ApiException) { assertEquals(401, e.status) }
        }
        try { api.authCheck(credentials.copy(token = "invalid")); fail("Invalid token accepted") } catch (e: ApiException) { assertEquals(401, e.status) }
        try { api.health(credentials.copy(certificateSha256 = "00".repeat(32))); fail("Invalid TLS pin accepted") } catch (_: javax.net.ssl.SSLException) { }
        try { api.pair(computer, args.getString("secret")!!, "Replay"); fail("Pairing replay accepted") } catch (e: ApiException) { assertEquals(401, e.status) }
        val prefs = SecurePreferences(context)
        try {
            prefs.save(credentials)
            assertEquals(credentials, SecurePreferences(context).load())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MainViewModel
                scenario.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java] }
                fun awaitOnline() {
                    for (attempt in 0 until 150) {
                        if (vm.state.value.online) return
                        Thread.sleep(100)
                    }
                    fail("Состояние ПК не получено")
                }
                awaitOnline()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertFalse(vm.state.value.online)
                assertTrue(vm.state.value.apps.isEmpty())
                assertNull(vm.state.value.monitoring)
                assertTrue(vm.state.value.history.isEmpty())
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                awaitOnline()
            }
            val raw = context.getSharedPreferences("secure", android.content.Context.MODE_PRIVATE)
            val encrypted = raw.getString("data", null)!!
            raw.edit().putString("data", "broken").commit()
            try { prefs.loadAll(); fail("Corrupt storage accepted") } catch (_: java.io.IOException) { }
            try { prefs.save(credentials); fail("Corrupt storage overwritten") } catch (_: java.io.IOException) { }
            assertEquals("broken", raw.getString("data", null))
            raw.edit().putString("data", encrypted).commit()
            assertEquals(credentials, prefs.load())
        } finally { prefs.clear() }
    }
}
