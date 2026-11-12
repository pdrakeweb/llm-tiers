package io.github.pdrakeweb.llmtiers.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pdrakeweb.llmtiers.NetworkKind
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.Reachability
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs on an emulator or device: the parts that need the real Android network stack. */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = OkHttpClient.Builder().privateCleartextOnly().build()

    @Test
    fun networkMonitorReportsTheDefaultNetworkWithItsOwnId() {
        AndroidNetworkMonitor(context).use { monitor ->
            val state = monitor.state.value
            assertTrue(state.online, "expected a network, got $state")
            assertNotEquals(NetworkKind.None, state.kind)
            assertTrue(state.id.value.startsWith("net:"), state.id.value)
        }
    }

    @Test
    fun wifiSocketFactoryGivesASocketOnWifiOrFailsLoudlyWithoutIt() {
        AndroidNetworkMonitor(context).use { monitor ->
            Thread.sleep(500) // let the Wi-Fi callback arrive
            val result = runCatching { monitor.wifiSocketFactory().createSocket().close() }
            if (monitor.state.value.kind == NetworkKind.Wifi) {
                assertTrue(result.isSuccess, "on Wi-Fi: ${result.exceptionOrNull()}")
            } else {
                assertTrue(result.isSuccess || result.exceptionOrNull() is java.io.IOException)
            }
        }
    }

    @Test
    fun plainHttpToLoopbackIsAllowed() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine().orEmpty().isNotEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            }
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute().use { response ->
                assertEquals("ok", response.body.string())
            }
        }
    }

    @Test
    fun plainHttpToAPublicHostIsRefusedBeforeTheRequestIsWritten() {
        assertFailsWith<CleartextNotAllowedException> {
            client.newCall(Request.Builder().url("http://example.com/").build()).execute().close()
        }
    }

    @Test
    fun httpsToAPublicHostIsNotAffected() {
        client.newCall(Request.Builder().url("https://example.com/").build()).execute().use { response ->
            assertTrue(response.code in 200..499)
        }
    }

    @Test
    fun localNetworkPermissionHelperMatchesThePlatform() {
        // The test APK targets the module's compile SDK, so on Android 17+ the permission is required.
        val expected = android.os.Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37 &&
            context.checkSelfPermission(ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED
        assertEquals(expected, needsLocalNetworkPermission(context))
    }

    @Test
    fun geminiNanoReportsItsRealStatusWithoutThrowing() = runBlocking {
        GeminiNano().use { nano ->
            val status = nano.status()
            val probe = nano.probe()
            if (status == NanoStatus.Available) {
                assertEquals(Reachability.Reachable, probe)
            } else {
                assertEquals(Reachability.Unreachable(ProviderFailure.ModelNotReady), probe)
            }
            println("Gemini Nano status on this device: $status")
        }
    }
}
