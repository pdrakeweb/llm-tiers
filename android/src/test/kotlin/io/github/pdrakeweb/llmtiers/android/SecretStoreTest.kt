package io.github.pdrakeweb.llmtiers.android

import android.content.SharedPreferences
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store's logic on the JVM: an in-memory SharedPreferences and a software AES-256-GCM cipher
 * stand in for the private file and the Android Keystore. The Keystore itself is exercised by
 * `SecretStoreOnDeviceTest`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecretStoreTest {
    private val prefs = MemoryPrefs()
    private val cipher = SoftwareCipher()
    private val store = SecretStore(prefs, cipher, UnconfinedTestDispatcher())

    @Test
    fun `a value round-trips exactly, through another instance too`() {
        for (value in listOf("sk-or-v1-abc123", "  spaces kept  ", "", "ключ ✓ 鍵")) {
            assertTrue(store.put("key", value))
            assertEquals(value, store.get("key"))
            assertEquals(value, SecretStore(prefs, cipher, UnconfinedTestDispatcher()).get("key"))
        }
        assertNull(store.get("never-set"))
    }

    @Test
    fun `only versioned ciphertext is written, with a fresh IV each time`() {
        store.put("key", "sk-or-v1-plaintext")
        val first = prefs.values["key"] as String
        assertTrue(first.startsWith("v1:"))
        assertFalse("plaintext" in first)
        assertFalse("plaintext" in String(Base64.getDecoder().decode(first.split(':')[2])))

        store.put("key", "sk-or-v1-plaintext")
        assertNotEquals(first, prefs.values["key"], "same value, different ciphertext")
    }

    @Test
    fun `a ciphertext is bound to its name - copied elsewhere it reads as null and is removed`() {
        store.put("openrouter", "secret")
        prefs.values["other"] = prefs.values["openrouter"]
        assertNull(store.get("other"))
        assertFalse(prefs.contains("other"))
        assertEquals("secret", store.get("openrouter"))
    }

    @Test
    fun `a ciphertext restored without its key reads as null and is removed, and a new value can be saved`() {
        store.put("key", "before the restore")
        store.put("token", "also before")
        // A restore or device transfer brings the file but never the Keystore key.
        val restored = SecretStore(prefs, SoftwareCipher(), UnconfinedTestDispatcher())
        assertNull(restored.get("key"))
        assertFalse(prefs.contains("key"))

        // On the new phone the Keystore has no key at all yet.
        val empty = SoftwareCipher().apply { key = null }
        val fresh = SecretStore(prefs, empty, UnconfinedTestDispatcher())
        assertNull(fresh.get("token"))
        assertFalse(prefs.contains("token"))
        assertNull(empty.key, "reading never creates a key")

        assertTrue(fresh.put("key", "entered again"))
        assertEquals("entered again", fresh.get("key"))
    }

    @Test
    fun `corrupted, altered or foreign entries read as null and are removed`() {
        store.put("good", "value")
        val good = prefs.values["good"] as String
        val (version, iv, sealed) = good.split(':')
        val flipped = Base64.getDecoder().decode(sealed).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val bad = mapOf(
            "garbage" to "nonsense",
            "wrong version" to "v2:$iv:$sealed",
            "not base64" to "v1:%%%:%%%",
            "short IV" to "$version:${Base64.getEncoder().encodeToString(ByteArray(8))}:$sealed",
            "short ciphertext" to "$version:$iv:${Base64.getEncoder().encodeToString(ByteArray(4))}",
            "altered" to "$version:$iv:${Base64.getEncoder().encodeToString(flipped)}",
            "extra part" to "$good:x",
        )
        for ((name, text) in bad) {
            prefs.values[name] = text
            assertNull(store.get(name), name)
            assertFalse(prefs.contains(name), name)
        }
        prefs.values["number"] = 42
        assertNull(store.get("number"), "something else stored under the name")
        assertFalse(prefs.contains("number"))
        assertEquals("value", store.get("good"))
    }

    @Test
    fun `a key Android invalidated is discarded with the entry, and the next put makes a new key`() {
        store.put("key", "value")
        cipher.openFailure = UnrecoverableKeyException("invalidated")
        assertNull(store.get("key"))
        assertFalse(prefs.contains("key"))
        assertEquals(1, cipher.keysDiscarded)

        cipher.openFailure = null
        assertTrue(store.put("key", "again"))
        assertEquals("again", store.get("key"))
    }

    @Test
    fun `a failure that may be temporary reads as null but keeps the entry`() {
        store.put("key", "value")
        for (failure in listOf(ProviderException("keystore busy"), KeyStoreException("binder"), IllegalStateException("x"))) {
            cipher.openFailure = failure
            assertNull(store.get("key"), "$failure")
            assertTrue(prefs.contains("key"), "$failure")
        }
        cipher.openFailure = null
        assertEquals("value", store.get("key"))
        assertEquals(0, cipher.keysDiscarded)
    }

    @Test
    fun `put reports failure and changes nothing`() {
        store.put("key", "old")
        cipher.sealFailures += ProviderException("keystore busy")
        assertFalse(store.put("key", "new"))
        assertEquals("old", store.get("key"))

        prefs.failCommits = true
        assertFalse(store.put("key", "new"))
        prefs.failCommits = false
        assertEquals("old", store.get("key"))
    }

    @Test
    fun `put replaces an unusable key once and succeeds`() {
        cipher.sealFailures += UnrecoverableKeyException("invalidated")
        assertTrue(store.put("key", "value"))
        assertEquals(1, cipher.keysDiscarded)
        assertEquals("value", store.get("key"))

        cipher.sealFailures += listOf(UnrecoverableKeyException("1"), UnrecoverableKeyException("2"))
        assertFalse(store.put("key", "other"), "a second failure gives up")
        assertEquals(3, cipher.keysDiscarded)
        assertNull(store.get("key"), "sealed under the key that was found unusable, so it is gone too")
        assertFalse(prefs.contains("key"))
    }

    @Test
    fun `remove and contains`() {
        assertFalse(store.contains("key"))
        store.put("key", "value")
        assertTrue(store.contains("key"))
        prefs.values["broken"] = "v1:x:y"
        assertFalse(store.contains("broken"), "an entry that cannot be read counts as absent")
        store.remove("key")
        assertFalse(store.contains("key"))
        assertNull(store.get("key"))
        store.remove("never-set")
    }

    @Test
    fun `observe emits the current value and each change to that name, from any instance`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val observed = SecretStore(prefs, cipher, dispatcher)
        val other = SecretStore(prefs, cipher, dispatcher)
        val seen = mutableListOf<String?>()
        val job = launch(dispatcher) { observed.observe("key").collect { seen += it } }

        observed.put("key", "one")
        observed.put("unrelated", "x")
        other.put("key", "two")
        other.put("key", "two")
        other.remove("key")
        job.cancelAndJoin()

        assertEquals(listOf(null, "one", "two", null), seen)
        assertEquals(0, prefs.listenerCount, "the listener is removed when collection stops")
    }

    @Test
    fun `toString never shows a value`() {
        store.put("key", "sk-or-v1-secret")
        assertEquals("SecretStore(llm_tiers_secrets)", store.toString())
        assertEquals("llm_tiers_secrets", SecretStore.PREFS_FILE_NAME)
    }

    @Test
    fun `the stored format round-trips and rejects anything else`() {
        val sealed = SecretCipher.Sealed(ByteArray(12) { it.toByte() }, ByteArray(20) { (it * 3).toByte() })
        val text = SecretFormat.encode(sealed)
        val back = assertNotNull(SecretFormat.decode(text))
        assertTrue(back.iv.contentEquals(sealed.iv) && back.ciphertext.contentEquals(sealed.ciphertext))
        for (bad in listOf("", "v1", "v1::", "v1:a:b:c", "v0:" + text.removePrefix("v1:"))) assertNull(SecretFormat.decode(bad), bad)
    }

    @Test
    fun `failures are classified - which mean the key is gone, which mean this entry is, which may pass`() {
        assertTrue(SecretStore.isKeyFailure(UnrecoverableKeyException()))
        assertTrue(SecretStore.isPermanent(AEADBadTagException()))
        assertFalse(SecretStore.isKeyFailure(ProviderException()) || SecretStore.isPermanent(ProviderException()))
        assertFalse(SecretStore.isKeyFailure(KeyStoreException()) || SecretStore.isPermanent(KeyStoreException()))
    }
}

/** AES-256-GCM in software, with a key that a test can lose or replace. */
private class SoftwareCipher : SecretCipher {
    var key: SecretKey? = newKey()
    var openFailure: Exception? = null
    val sealFailures = mutableListOf<Exception>()
    var keysDiscarded = 0

    override fun seal(plaintext: ByteArray, aad: ByteArray): SecretCipher.Sealed {
        sealFailures.removeFirstOrNull()?.let { throw it }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key ?: newKey().also { key = it })
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return SecretCipher.Sealed(cipher.iv, ciphertext)
    }

    override fun open(sealed: SecretCipher.Sealed, aad: ByteArray): ByteArray? {
        openFailure?.let { throw it }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key ?: return null, GCMParameterSpec(128, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    override fun discardKey() {
        keysDiscarded++
        key = null
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

/** Enough of SharedPreferences for the store: strings, removal, change listeners, failing commits. */
private class MemoryPrefs : SharedPreferences {
    val values = linkedMapOf<String, Any?>()
    var failCommits = false
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    val listenerCount: Int get() = listeners.size

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String?, defValue: String?): String? = (values[key] as String?) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val puts = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = edited { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = edited { puts[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = edited { puts[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = edited { puts[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = edited { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = edited { puts[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = edited { removals += key }
        override fun clear(): SharedPreferences.Editor = edited { removals += values.keys }

        private inline fun edited(change: () -> Unit): SharedPreferences.Editor {
            change()
            return this
        }

        override fun commit(): Boolean {
            if (failCommits) return false
            apply()
            return true
        }

        override fun apply() {
            removals.forEach { values.remove(it) }
            puts.forEach { (k, v) -> values[k] = v }
            (removals + puts.keys).forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemoryPrefs, key) } }
        }
    }
}
