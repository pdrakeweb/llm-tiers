package io.github.pdrakeweb.llmtiers.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.Part
import io.github.pdrakeweb.llmtiers.PreparedRequest
import io.github.pdrakeweb.llmtiers.Provider
import io.github.pdrakeweb.llmtiers.ProviderEvent
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.Reachability
import io.github.pdrakeweb.llmtiers.Role
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import java.io.File

/** One turn of earlier conversation handed to a local model. */
internal data class LocalTurn(val fromUser: Boolean, val text: String)

/** A loaded on-device model; the real one is LiteRT-LM, tests use a fake. */
internal interface LocalModel {
    /** Emits text chunks for one reply. */
    fun generate(system: String?, history: List<LocalTurn>, prompt: String): Flow<String>
    fun close()
}

/** Loads a model file. Slow (seconds) and blocking; called off the main thread. */
internal fun interface LocalModelLoader {
    fun load(file: File): LocalModel
}

/** Where LiteRT-LM runs the model. */
public enum class LiteRtBackend {
    /** Works on every supported device. */
    Cpu,

    /** Faster for larger models on phones with a capable GPU; a model that cannot use it fails to load. */
    Gpu,
}

/**
 * A downloaded open model run on the device with LiteRT-LM. Nothing leaves the device.
 *
 * @param modelFile returns the verified model file, or null while it is not on the device. With
 *   the `:model-manager` module: `{ (manager.state(spec) as? ModelState.Ready)?.file }`.
 * @param maxContext the model's context length in tokens.
 *
 * Loading a model takes seconds, far longer than a first-token timeout. The first request starts
 * the load and, if the router gives up waiting, the load carries on in the background so the next
 * request finds the model ready. Call [warmUp] when the app opens to avoid that first miss. One
 * request runs at a time; a second one meanwhile gets `Busy`.
 */
public class LiteRtModel internal constructor(
    id: String,
    private val modelFile: suspend () -> File?,
    private val maxContext: Int,
    private val loader: LocalModelLoader,
    private val dispatcher: CoroutineDispatcher,
) : Provider, AutoCloseable {
    public constructor(
        id: String,
        maxContext: Int = 4096,
        backend: LiteRtBackend = LiteRtBackend.Cpu,
        modelFile: suspend () -> File?,
    ) : this(id, modelFile, maxContext, LiteRtLoader(maxContext, backend), Dispatchers.IO)

    override val id: ProviderId = ProviderId(id)
    override val tier: Tier get() = Tier.ON_DEVICE
    override val accepts: Set<Sensitivity> = setOf(Sensitivity.Public, Sensitivity.Personal)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val generating = Mutex()
    private val lock = Any()
    private var loading: Pair<File, Deferred<LocalModel>>? = null
    private var loaded: LocalModel? = null
    private var closed = false

    /**
     * Why the model last failed to load (the engine's own message about the model file or the
     * device), or null. For a settings screen; it never contains request or response content.
     */
    @Volatile
    public var lastLoadError: String? = null
        private set

    override suspend fun capabilities(): Capabilities = Capabilities(text = true, vision = false, jsonSchema = false, maxContext = maxContext)

    /** Ready means the file is on the device; the model itself is loaded on first use. */
    override suspend fun probe(): Reachability =
        if (currentFile() != null) Reachability.Reachable else Reachability.Unreachable(ProviderFailure.ModelNotReady)

    /** Starts loading the model so the first request does not pay for it. Returns whether it loaded. */
    public suspend fun warmUp(): Boolean {
        val file = currentFile() ?: return false
        return try {
            model(file).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            forget(file) // a failed load is not remembered; the next attempt starts afresh
            lastLoadError = describe(e)
            false
        }
    }

    override fun stream(request: PreparedRequest): Flow<ProviderEvent> = flow {
        val file = currentFile()
        if (file == null) {
            emit(ProviderEvent.Failed(ProviderFailure.ModelNotReady))
            return@flow
        }
        if (!generating.tryLock()) {
            emit(ProviderEvent.Failed(ProviderFailure.Busy))
            return@flow
        }
        try {
            val model = try {
                model(file).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not a usable model (corrupt, wrong format, unsupported device).
                forget(file)
                lastLoadError = describe(e)
                emit(ProviderEvent.Failed(ProviderFailure.ModelNotReady))
                return@flow
            }
            val messages = request.renderedMessages()
            fun text(parts: List<Part>) = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            val system = messages.filter { it.role == Role.System }.joinToString("\n") { text(it.parts) }.ifEmpty { null }
            val turns = messages.filter { it.role != Role.System }.map { LocalTurn(it.role == Role.User, text(it.parts)) }
            val prompt = turns.lastOrNull { it.fromUser }?.text.orEmpty()
            val history = turns.take(turns.indexOfLast { it.fromUser }.coerceAtLeast(0))
            try {
                model.generate(system, history, prompt).collect { chunk ->
                    if (chunk.isNotEmpty()) emit(ProviderEvent.Token(chunk))
                }
                emit(ProviderEvent.Done(null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ProviderEvent.Failed(ProviderFailure.Other(e::class.simpleName ?: "exception")))
            }
        } finally {
            generating.unlock()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            loaded?.let { model -> runCatching { model.close() } }
            loaded = null
            loading = null
        }
        scope.cancel()
    }

    private suspend fun currentFile(): File? = try {
        modelFile()?.takeIf { it.isFile }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The load runs in this provider's own scope, so a caller that stops waiting does not cancel it. */
    private fun model(file: File): Deferred<LocalModel> = synchronized(lock) {
        loading?.let { (current, deferred) -> if (current == file) return deferred }
        // A different file (the app switched models): release the old one before loading the new.
        loaded?.let { old -> runCatching { old.close() } }
        loaded = null
        scope.async {
            val model = loader.load(file)
            lastLoadError = null
            // A load cannot be interrupted; if the provider was closed meanwhile, release it now.
            synchronized(lock) { if (closed) runCatching { model.close() } else loaded = model }
            model
        }.also { loading = file to it }
    }

    private fun describe(e: Exception) = (e::class.simpleName ?: "error") + (e.message?.let { ": " + it.take(300) } ?: "")

    private fun forget(file: File) = synchronized(lock) { if (loading?.first == file) loading = null }
}

internal class LiteRtLoader(private val maxContext: Int, private val backend: LiteRtBackend) : LocalModelLoader {
    override fun load(file: File): LocalModel {
        val engine = Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = if (backend == LiteRtBackend.Gpu) Backend.GPU() else Backend.CPU(),
                maxNumTokens = maxContext,
            )
        )
        engine.initialize()
        return LiteRtLocalModel(engine)
    }
}

internal class LiteRtLocalModel(private val engine: Engine) : LocalModel {
    override fun generate(system: String?, history: List<LocalTurn>, prompt: String): Flow<String> = flow {
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = system?.let { Contents.of(it) },
                initialMessages = history.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) },
            )
        )
        try {
            conversation.sendMessageAsync(prompt).collect { message ->
                emit(message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
            }
        } catch (e: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw e
        } finally {
            runCatching { conversation.close() }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() = engine.close()
}
