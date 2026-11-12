package io.github.pdrakeweb.llmtiers.android

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/** Whether Gemini Nano can be used on this device right now. */
public enum class NanoStatus { Unavailable, Downloadable, Downloading, Available }

/** What the provider needs from the on-device model; the real one is ML Kit, tests use a fake. */
internal interface NanoEngine {
    suspend fun status(): NanoStatus
    suspend fun tokenLimit(): Int

    /** Emits text chunks. Throws [NanoException] on failure. */
    fun generate(prompt: String, image: ByteArray?): Flow<String>

    /** Emits download progress in bytes and completes when the model is ready. */
    fun download(): Flow<Long>
    fun close()
}

internal class NanoException(val failure: ProviderFailure) : Exception(null, null, false, false)

/**
 * Gemini Nano on the device, through the ML Kit GenAI Prompt API (which talks to Android's AICore
 * system service). Nothing leaves the device.
 *
 * It only works on phones that ship Gemini Nano, with a locked bootloader, and only while the app
 * is in the foreground. Anywhere else [probe] reports `ModelNotReady` and the router moves on.
 * The context window is small (about 4,000 tokens), so long prompts fall through to a provider
 * with a longer one.
 */
public class GeminiNano internal constructor(
    id: String,
    private val engine: NanoEngine,
) : Provider, AutoCloseable {
    public constructor(id: String = "gemini-nano") : this(id, MlKitNanoEngine())

    override val id: ProviderId = ProviderId(id)
    override val tier: Tier get() = Tier.ON_DEVICE
    override val accepts: Set<Sensitivity> = setOf(Sensitivity.Public, Sensitivity.Personal)

    @Volatile private var limit: Int? = null

    override suspend fun capabilities(): Capabilities = Capabilities(
        text = true,
        vision = true,
        jsonSchema = false,
        maxContext = limit ?: DEFAULT_CONTEXT,
    )

    /** Feature status, for a settings screen ("download the on-device model"). */
    public suspend fun status(): NanoStatus = try {
        engine.status()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NanoStatus.Unavailable
    }

    /** Downloads the model. Emits bytes downloaded so far and completes when it is ready. */
    public fun download(): Flow<Long> = engine.download()

    override suspend fun probe(): Reachability {
        if (status() != NanoStatus.Available) return Reachability.Unreachable(ProviderFailure.ModelNotReady)
        if (limit == null) limit = runCatching { engine.tokenLimit() }.getOrNull()?.takeIf { it > 0 }
        return Reachability.Reachable
    }

    override fun stream(request: PreparedRequest): Flow<ProviderEvent> = flow {
        val image = request.messages.flatMap { it.parts }.filterIsInstance<Part.Image>().firstOrNull()?.bytes
        try {
            engine.generate(render(request), image).collect { chunk ->
                if (chunk.isNotEmpty()) emit(ProviderEvent.Token(chunk))
            }
            emit(ProviderEvent.Done(null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NanoException) {
            emit(ProviderEvent.Failed(if (e.failure is ProviderFailure.ContextTooLong) ProviderFailure.ContextTooLong(limit ?: DEFAULT_CONTEXT) else e.failure))
        } catch (e: Exception) {
            emit(ProviderEvent.Failed(ProviderFailure.Other(e::class.simpleName ?: "exception")))
        }
    }

    override fun close(): Unit = engine.close()

    internal companion object {
        const val DEFAULT_CONTEXT = 4000

        /** The Prompt API takes one prompt, so the conversation is flattened into it. */
        fun render(request: PreparedRequest): String {
            val messages = request.renderedMessages()
            fun text(parts: List<Part>) = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            val system = messages.filter { it.role == Role.System }.joinToString("\n") { text(it.parts) }
            val turns = messages.filter { it.role != Role.System }
            val body = if (turns.size == 1) {
                text(turns.single().parts)
            } else {
                turns.joinToString("\n") { (if (it.role == Role.User) "User: " else "Assistant: ") + text(it.parts) } + "\nAssistant:"
            }
            return if (system.isEmpty()) body else "$system\n\n$body"
        }
    }
}

/** Maps an ML Kit GenAI error code to a typed failure. */
internal fun genAiFailure(errorCode: Int): ProviderFailure = when (errorCode) {
    // Out of quota, or called from the background: both mean "not right now".
    GenAiException.ErrorCode.BUSY,
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> ProviderFailure.Busy
    GenAiException.ErrorCode.NOT_AVAILABLE,
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
    GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
    GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> ProviderFailure.ModelNotReady
    GenAiException.ErrorCode.REQUEST_TOO_LARGE -> ProviderFailure.ContextTooLong(0)
    else -> ProviderFailure.Other("genai $errorCode")
}

internal class MlKitNanoEngine : NanoEngine {
    private val model: GenerativeModel by lazy { Generation.getClient() }

    override suspend fun status(): NanoStatus = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> NanoStatus.Available
        FeatureStatus.DOWNLOADABLE -> NanoStatus.Downloadable
        FeatureStatus.DOWNLOADING -> NanoStatus.Downloading
        else -> NanoStatus.Unavailable
    }

    override suspend fun tokenLimit(): Int = model.getTokenLimit()

    override fun generate(prompt: String, image: ByteArray?): Flow<String> = flow {
        val request = if (image != null) {
            generateContentRequest(ImagePart(image), TextPart(prompt)) { }
        } else {
            generateContentRequest(TextPart(prompt)) { }
        }
        try {
            model.generateContentStream(request).collect { response -> emit(response.candidates.firstOrNull()?.text.orEmpty()) }
        } catch (e: GenAiException) {
            throw NanoException(genAiFailure(e.errorCode))
        }
    }

    override fun download(): Flow<Long> = model.download()
        .transformWhile { status ->
            when (status) {
                is DownloadStatus.DownloadProgress -> emit(status.totalBytesDownloaded)
                is DownloadStatus.DownloadFailed -> throw NanoException(genAiFailure(status.e.errorCode))
                else -> Unit
            }
            status !is DownloadStatus.DownloadCompleted
        }

    override fun close() = model.close()
}
