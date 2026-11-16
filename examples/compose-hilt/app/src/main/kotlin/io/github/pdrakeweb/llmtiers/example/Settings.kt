package io.github.pdrakeweb.llmtiers.example

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pdrakeweb.llmtiers.android.SecretStore
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

/**
 * Plain settings in SharedPreferences; the API key goes through the library's [SecretStore],
 * which keeps it encrypted under a key held in the Android Keystore.
 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun load() = AiSettings(
        remoteUrl = prefs.getString("remoteUrl", "").orEmpty(),
        remoteModel = prefs.getString("remoteModel", "").orEmpty(),
        apiKey = secrets.get(REMOTE_KEY),
        modelId = prefs.getString("modelId", null),
    )

    fun save(settings: AiSettings) {
        prefs.edit()
            .putString("remoteUrl", settings.remoteUrl.trim())
            .putString("remoteModel", settings.remoteModel.trim())
            .putString("modelId", settings.modelId)
            .apply()
        val key = settings.apiKey?.trim()
        if (key.isNullOrEmpty()) secrets.remove(REMOTE_KEY) else secrets.put(REMOTE_KEY, key)
    }

    private companion object {
        const val REMOTE_KEY = "remoteKey"
    }
}
