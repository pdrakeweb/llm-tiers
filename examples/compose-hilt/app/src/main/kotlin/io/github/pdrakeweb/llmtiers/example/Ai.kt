package io.github.pdrakeweb.llmtiers.example

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.pdrakeweb.llmtiers.Event
import io.github.pdrakeweb.llmtiers.Item
import io.github.pdrakeweb.llmtiers.Logger
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.ProviderInfo
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.TieredAi
import io.github.pdrakeweb.llmtiers.android.AndroidNetworkMonitor
import io.github.pdrakeweb.llmtiers.android.GeminiNano
import io.github.pdrakeweb.llmtiers.android.LiteRtModel
import io.github.pdrakeweb.llmtiers.android.privateCleartextOnly
import io.github.pdrakeweb.llmtiers.android.requiringLocalNetworkPermission
import io.github.pdrakeweb.llmtiers.modelmanager.ModelManager
import io.github.pdrakeweb.llmtiers.modelmanager.ModelSpec
import io.github.pdrakeweb.llmtiers.modelmanager.ModelState
import io.github.pdrakeweb.llmtiers.modelmanager.OpenModels
import io.github.pdrakeweb.llmtiers.openai.OpenAiCompatible
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** The one thing screens need from the library. A test replaces it with fakes. */
fun interface AiStreams {
    fun stream(request: Request): Flow<Event>
}

/**
 * Carries the library's approval question to whichever screen is showing, and the user's answer
 * back. The library calls [ask] before personal data may leave the device; the screen observes
 * [pending], shows it, and calls [Pending.answer].
 */
@Singleton
class ApprovalBroker @Inject constructor() {
    class Pending(val items: List<String>, val provider: String, private val reply: CompletableDeferred<Boolean>) {
        fun answer(allow: Boolean) {
            reply.complete(allow)
        }
    }

    val pending = MutableStateFlow<Pending?>(null)

    suspend fun ask(items: List<Item>, provider: ProviderInfo): Boolean {
        val reply = CompletableDeferred<Boolean>()
        val question = Pending(items.map { it.value }, provider.id.value, reply)
        pending.value = question
        try {
            return reply.await()
        } finally {
            // Also runs when the request is cancelled or the library's approval timeout passes.
            pending.compareAndSet(question, null)
        }
    }
}

/**
 * Owns the single [TieredAi] for the app. It lives as long as the process so that what the
 * library learns about each provider (health, timings) is kept between screens. It is rebuilt
 * only when the user changes the settings.
 */
@Singleton
class AiGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val monitor: AndroidNetworkMonitor,
    private val http: OkHttpClient,
    private val approvals: ApprovalBroker,
    private val models: ModelManager,
) : AiStreams {
    private val nano = GeminiNano()
    private var built: Pair<AiSettings, TieredAi>? = null
    private var local: Pair<ModelSpec, LiteRtModel>? = null

    override fun stream(request: Request): Flow<Event> = ai().stream(request)

    /** Call after the user answers the local-network permission prompt: earlier failures no longer apply. */
    suspend fun forgetRemoteHealth() = ai().health.reset(ProviderId(REMOTE))

    /** Loads the chosen on-device model ahead of the first request. Returns null when loaded, or why not. */
    suspend fun warmUpLocalModel(): String? {
        val spec = settings.load().model() ?: return "No model chosen"
        val provider = localModel(spec)
        return if (provider.warmUp()) null else provider.lastLoadError ?: "The model is not on the device yet"
    }

    @Synchronized
    private fun ai(): TieredAi {
        val current = settings.load()
        built?.let { (was, ai) -> if (was == current) return ai }
        return build(current).also { built = current to it }
    }

    private fun build(s: AiSettings): TieredAi = TieredAi {
        // Registration order is the default order of preference.
        if (s.remoteUrl.isNotBlank()) {
            val remote = OpenAiCompatible(REMOTE, s.remoteUrl, s.remoteModel, apiKey = s.apiKey, client = http)
            // A server on the home network needs the Android 17 local-network permission: gate it.
            provider(if (isHomeNetworkUrl(s.remoteUrl)) remote.requiringLocalNetworkPermission(context) else remote, quality = 0.9)
        }
        s.model()?.let { provider(localModel(it), quality = 0.5) }
        provider(nano, quality = 0.3)

        network = monitor
        approve { request, provider -> approvals.ask(request.personalItems, provider) }
        // Content-free by construction: provider ids, timings and failure types only.
        logger = Logger { Log.i("llm-tiers", "${it.event} ${it.provider} ${it.outcome} ${it.elapsedMillis}ms") }
    }

    @Synchronized
    private fun localModel(spec: ModelSpec): LiteRtModel {
        local?.let { (was, provider) -> if (was == spec) return provider else provider.close() }
        return LiteRtModel(LOCAL, spec.contextTokens) { (models.state(spec) as? ModelState.Ready)?.file }
            .also { local = spec to it }
    }

    companion object {
        const val REMOTE = "remote"
        const val LOCAL = "local"
    }
}

/**
 * A plain-HTTP URL that is not this device itself. Loopback addresses are exempt from the
 * local-network permission, so a server running on the phone must not be gated on it.
 */
fun isHomeNetworkUrl(url: String): Boolean =
    url.startsWith("http://") && !Regex("""^http://(127\.|localhost[:/]|\[::1])""").containsMatchIn(url)

fun AiSettings.model(): ModelSpec? = OpenModels.all.firstOrNull { it.id == modelId }

@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides @Singleton
    fun networkMonitor(@ApplicationContext context: Context) = AndroidNetworkMonitor(context)

    /** One client for every remote provider, so the plain-HTTP rule cannot be skipped by accident. */
    @Provides @Singleton
    fun httpClient(): OkHttpClient = OkHttpClient.Builder().privateCleartextOnly().build()

    @Provides @Singleton
    fun modelManager(@ApplicationContext context: Context) = ModelManager(context)

    @Provides
    fun aiStreams(gateway: AiGateway): AiStreams = gateway
}
