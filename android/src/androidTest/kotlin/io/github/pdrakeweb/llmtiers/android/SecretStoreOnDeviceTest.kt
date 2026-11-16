package io.github.pdrakeweb.llmtiers.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [SecretStore] against the real Android Keystore and SharedPreferences. */
@RunWith(AndroidJUnit4::class)
class SecretStoreOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val raw = context.getSharedPreferences(SecretStore.PREFS_FILE_NAME, Context.MODE_PRIVATE)
    private val secret = "sk-or-v1-do-not-store-in-plaintext"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before
    @After
    fun clean() {
        raw.edit().clear().commit()
        keyStore().deleteEntry(SecretStore.KEY_ALIAS)
    }

    @Test
    fun valuesRoundTripThroughTheKeystoreAndAcrossInstances() {
        val store = SecretStore(context)
        assertTrue(store.put("openrouter", secret))
        assertEquals(secret, store.get("openrouter"))
        assertEquals(secret, SecretStore(context).get("openrouter"))
        assertTrue(store.contains("openrouter"))
        store.remove("openrouter")
        assertNull(store.get("openrouter"))
        assertFalse(store.contains("openrouter"))
    }

    @Test
    fun theKeyIsInTheKeystoreAndCannotBeReadOut() {
        SecretStore(context).put("openrouter", secret)
        val key = assertNotNull(keyStore().getKey(SecretStore.KEY_ALIAS, null) as? SecretKey)
        assertEquals("AES", key.algorithm)
        assertNull(key.encoded, "a Keystore key has no exportable bytes")
    }

    @Test
    fun onlyCiphertextReachesTheDisk() {
        SecretStore(context).put("openrouter", secret)
        val stored = assertNotNull(raw.getString("openrouter", null))
        assertTrue(stored.startsWith("v1:"))
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${SecretStore.PREFS_FILE_NAME}.xml")
        assertTrue(file.isFile, "$file")
        assertFalse(secret in file.readText())
        assertFalse("do-not-store" in file.readText())
    }

    @Test
    fun aCiphertextWhoseKeystoreKeyIsGoneReadsAsNullAndIsRemoved() {
        val store = SecretStore(context)
        store.put("openrouter", secret)
        keyStore().deleteEntry(SecretStore.KEY_ALIAS) // what a restore to another phone looks like
        assertNull(store.get("openrouter"))
        assertFalse(raw.contains("openrouter"))
        assertTrue(store.put("openrouter", "entered again"), "a new key is made")
        assertEquals("entered again", store.get("openrouter"))
    }

    @Test
    fun aCiphertextSealedByAnotherKeyReadsAsNullAndIsRemoved() {
        val store = SecretStore(context)
        store.put("openrouter", secret)
        val before = raw.getString("openrouter", null)
        keyStore().deleteEntry(SecretStore.KEY_ALIAS)
        store.put("other", "x") // the Keystore now holds a different key
        raw.edit().putString("openrouter", before).commit()
        assertNull(store.get("openrouter"))
        assertFalse(raw.contains("openrouter"))
        assertEquals("x", store.get("other"))
    }

    @Test
    fun alteredOrMovedCiphertextReadsAsNullAndIsRemoved() {
        val store = SecretStore(context)
        store.put("openrouter", secret)
        val (version, iv, sealed) = raw.getString("openrouter", null)!!.split(':')
        val flipped = Base64.getDecoder().decode(sealed).also { it[3] = (it[3].toInt() xor 0x40).toByte() }
        raw.edit()
            .putString("altered", "$version:$iv:${Base64.getEncoder().encodeToString(flipped)}")
            .putString("moved", "$version:$iv:$sealed")
            .putString("garbage", "not a secret")
            .commit()
        for (name in listOf("altered", "moved", "garbage")) {
            assertNull(store.get(name), name)
            assertFalse(raw.contains(name), name)
        }
        assertEquals(secret, store.get("openrouter"))
    }

    @Test
    fun observeSeesEveryChange() = runBlocking {
        val store = SecretStore(context)
        val seen = CopyOnWriteArrayList<String?>()
        suspend fun awaitSize(n: Int) = withTimeout(10.seconds) { while (seen.size < n) delay(20) }
        val job = launch { store.observe("watched").collect { seen += it } }
        awaitSize(1)
        store.put("watched", "one")
        awaitSize(2)
        SecretStore(context).put("watched", "two")
        awaitSize(3)
        store.remove("watched")
        awaitSize(4)
        job.cancel()
        assertEquals(listOf(null, "one", "two", null), seen.toList())
    }

    @Test
    fun toStringNeverShowsAValue() {
        val store = SecretStore(context)
        store.put("openrouter", secret)
        assertFalse(secret in store.toString())
    }
}
