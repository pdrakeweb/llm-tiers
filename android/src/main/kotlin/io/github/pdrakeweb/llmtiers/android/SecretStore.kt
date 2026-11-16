package io.github.pdrakeweb.llmtiers.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets an app keeps on the device, such as API keys the user typed in, stored encrypted.
 *
 * Each value is encrypted with AES-256-GCM under one key held in the Android Keystore, the
 * system service that lets an app use a key without ever being able to read it out. The key is
 * created on first use and cannot be exported. Only ciphertext is written, to the private
 * SharedPreferences file [PREFS_FILE_NAME]. Each entry's name is bound into its encryption, so a
 * ciphertext copied under another name does not decrypt.
 *
 * Reads never throw:
 * - A value that can never be decrypted reads as null and its entry is deleted, so the app simply
 *   asks the user again. That covers a missing Keystore key (app data restored from a backup or
 *   moved to a new phone: Keystore keys are never backed up or transferred, so the ciphertext
 *   arrives without its key), a key Android has invalidated, and corrupted or altered text.
 * - A failure that may be temporary, such as a busy Keystore service, also reads as null but
 *   keeps the entry for the next read.
 *
 * Exclude [PREFS_FILE_NAME] from cloud backup and device transfer (docs/ANDROID.md has the
 * rules): restored, it can only fail to decrypt.
 *
 * Each call does Keystore work, typically a few milliseconds; call it off the main thread where
 * you can. Values never appear in [toString] or in exceptions, and the class does not log.
 */
@SuppressLint("UseKtx") // the KTX `edit {}` helper needs androidx.core, which this module does not depend on
public class SecretStore internal constructor(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher,
    private val dispatcher: CoroutineDispatcher,
) {
    /** The app's secret store. Every instance in a process shares the same file and key. */
    public constructor(context: Context) : this(
        (context.applicationContext ?: context).getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE),
        KeystoreCipher(KEY_ALIAS),
        Dispatchers.IO,
    )

    /**
     * Encrypts [value] and stores it under [name], replacing any earlier value. The value is
     * stored exactly as given; trim it first if that is what you want.
     *
     * @return true once it is encrypted and written; false if the Keystore could not encrypt it
     *   or the write failed, in which case nothing changed.
     */
    @SuppressLint("ApplySharedPref") // the result says whether the value reached the disk
    public fun put(name: String, value: String): Boolean {
        val sealed = seal(value.toByteArray(Charsets.UTF_8), aad(name)) ?: return false
        return prefs.edit().putString(name, SecretFormat.encode(sealed)).commit()
    }

    /** The value stored under [name], or null if there is none or it can no longer be decrypted. */
    public fun get(name: String): String? {
        val stored = try {
            prefs.getString(name, null)
        } catch (_: ClassCastException) {
            return discard(name) // something other than a secret was written under this name
        } ?: return null
        val sealed = SecretFormat.decode(stored) ?: return discard(name)
        return try {
            val plain = cipher.open(sealed, aad(name)) ?: return discard(name) // the key is gone
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            when {
                isKeyFailure(e) -> {
                    discardKey()
                    discard(name)
                }
                isPermanent(e) -> discard(name)
                else -> null // possibly temporary: keep the entry
            }
        }
    }

    /** Deletes the value stored under [name], if any. */
    public fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    /** True when [get] would return a value. A stored value that cannot be decrypted counts as absent. */
    public fun contains(name: String): Boolean = get(name) != null

    /**
     * The value under [name] now and after every change, by this or any other instance in the
     * process. Emits null while there is none. Decryption runs on a background dispatcher.
     */
    public fun observe(name: String): Flow<String?> = callbackFlow {
        // SharedPreferences holds listeners weakly; this flow holds it strongly until it closes.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == name) trySend(Unit) // null: the file was cleared (Android 11+)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .conflate()
        .map { get(name) }
        .distinctUntilChanged()
        .flowOn(dispatcher)

    override fun toString(): String = "SecretStore($PREFS_FILE_NAME)"

    private fun seal(plain: ByteArray, aad: ByteArray): SecretCipher.Sealed? {
        repeat(2) {
            try {
                return cipher.seal(plain, aad)
            } catch (e: Exception) {
                if (!isKeyFailure(e)) return null
                discardKey() // unusable for good: the next attempt creates a new key
            }
        }
        return null
    }

    private fun discardKey() {
        try {
            cipher.discardKey()
        } catch (_: Exception) {
            // The Keystore is unavailable; the next call tries again.
        }
    }

    private fun discard(name: String): String? {
        prefs.edit().remove(name).apply()
        return null
    }

    private fun aad(name: String): ByteArray = "${SecretFormat.VERSION}:$name".toByteArray(Charsets.UTF_8)

    public companion object {
        /**
         * The SharedPreferences file that holds the ciphertext, `llm_tiers_secrets.xml` in the
         * app's `shared_prefs` directory. Exclude it from backup and device transfer.
         */
        public const val PREFS_FILE_NAME: String = "llm_tiers_secrets"

        internal const val KEY_ALIAS: String = "llm_tiers_secret_store"

        /** The Keystore key itself is unusable for good. */
        internal fun isKeyFailure(e: Exception): Boolean =
            e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException

        /** This ciphertext will never decrypt: wrong key, altered, or not ours. */
        internal fun isPermanent(e: Exception): Boolean =
            e is AEADBadTagException || e is InvalidAlgorithmParameterException
    }
}

/** The encryption behind [SecretStore]; a seam so the store's logic can be tested on the JVM. */
internal interface SecretCipher {
    class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    fun seal(plaintext: ByteArray, aad: ByteArray): Sealed

    /** The plaintext, or null when the key that sealed it no longer exists. */
    fun open(sealed: Sealed, aad: ByteArray): ByteArray?

    /** Deletes the key, so the next [seal] creates a new one. */
    fun discardKey()
}

/** `v1:<base64 IV>:<base64 ciphertext and tag>`. */
internal object SecretFormat {
    const val VERSION: String = "v1"
    const val IV_BYTES: Int = 12
    const val TAG_BYTES: Int = 16

    fun encode(sealed: SecretCipher.Sealed): String {
        val base64 = Base64.getEncoder()
        return "$VERSION:${base64.encodeToString(sealed.iv)}:${base64.encodeToString(sealed.ciphertext)}"
    }

    /** Null for anything this version did not write. */
    fun decode(text: String): SecretCipher.Sealed? {
        val parts = text.split(':')
        if (parts.size != 3 || parts[0] != VERSION) return null
        return try {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(parts[1])
            val ciphertext = decoder.decode(parts[2])
            if (iv.size != IV_BYTES || ciphertext.size < TAG_BYTES) null else SecretCipher.Sealed(iv, ciphertext)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** AES-256-GCM under a non-exportable Android Keystore key. */
internal class KeystoreCipher(private val alias: String) : SecretCipher {
    override fun seal(plaintext: ByteArray, aad: ByteArray): SecretCipher.Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true)) // the Keystore picks a fresh random IV
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return SecretCipher.Sealed(cipher.iv, ciphertext)
    }

    override fun open(sealed: SecretCipher.Sealed, aad: ByteArray): ByteArray? {
        val key = key(create = false) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(SecretFormat.TAG_BYTES * 8, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    override fun discardKey() {
        synchronized(LOCK) { keyStore().deleteEntry(alias) }
    }

    /** One lock for the process, so two instances never create the key twice. */
    private fun key(create: Boolean): SecretKey? = synchronized(LOCK) {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val LOCK = Any()
    }
}
