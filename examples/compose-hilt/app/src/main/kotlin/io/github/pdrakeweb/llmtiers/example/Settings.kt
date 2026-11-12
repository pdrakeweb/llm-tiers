package io.github.pdrakeweb.llmtiers.example

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class ExampleApp : Application()

data class AiSettings(
    val remoteUrl: String = "",
    val remoteModel: String = "",
    val apiKey: String? = null,
    /** Id of the chosen entry in `OpenModels.all`, or null for none. */
    val modelId: String? = null,
)

/** Plain settings in SharedPreferences; the API key goes through [KeyVault]. */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context, private val vault: KeyVault) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun load() = AiSettings(
        remoteUrl = prefs.getString("remoteUrl", "").orEmpty(),
        remoteModel = prefs.getString("remoteModel", "").orEmpty(),
        apiKey = vault.get("remoteKey"),
        modelId = prefs.getString("modelId", null),
    )

    fun save(settings: AiSettings) {
        prefs.edit()
            .putString("remoteUrl", settings.remoteUrl.trim())
            .putString("remoteModel", settings.remoteModel.trim())
            .putString("modelId", settings.modelId)
            .apply()
        vault.put("remoteKey", settings.apiKey)
    }
}

/**
 * Keeps secrets encrypted with a key that never leaves the Android Keystore. The library takes an
 * API key as a plain `String`; where the app keeps it is the app's job, and this is a small,
 * dependency-free way to do it.
 */
@Singleton
class KeyVault @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("key_vault", Context.MODE_PRIVATE)

    fun put(name: String, secret: String?) {
        if (secret.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(secret.trim().toByteArray())
        prefs.edit().putString(name, encode(cipher.iv) + ":" + encode(sealed)).apply()
    }

    fun get(name: String): String? = try {
        val (iv, sealed) = prefs.getString(name, null)?.split(":")?.map { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(sealed))
    } catch (e: Exception) {
        null // the Keystore key was lost (for example after a restore): the user enters the key again
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "llm_tiers_example_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
