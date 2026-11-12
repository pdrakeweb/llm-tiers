package io.github.pdrakeweb.llmtiers

import kotlin.time.Duration

public enum class Role { System, User, Assistant }

/** One piece of a message. `toString` never includes content. */
public sealed interface Part {
    public class Text(public val text: String) : Part {
        override fun equals(other: Any?): Boolean = other is Text && other.text == text
        override fun hashCode(): Int = text.hashCode()
        override fun toString(): String = "Text(${text.length} chars)"
    }

    public class Image(public val bytes: ByteArray, public val mimeType: String) : Part {
        override fun toString(): String = "Image($mimeType, ${bytes.size} bytes)"
    }
}

public data class Message(val role: Role, val parts: List<Part>)

public fun system(text: String): Message = Message(Role.System, listOf(Part.Text(text)))
public fun user(text: String): Message = Message(Role.User, listOf(Part.Text(text)))
public fun assistant(text: String): Message = Message(Role.Assistant, listOf(Part.Text(text)))

/**
 * A piece of app data that accompanies a request, tagged with how sensitive it is.
 * `toString` never includes [value].
 */
public class Item(
    public val value: String,
    public val sensitivity: Sensitivity,
    public val label: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is Item && other.value == value && other.sensitivity == sensitivity && other.label == label

    override fun hashCode(): Int = 31 * (31 * value.hashCode() + sensitivity.hashCode()) + (label?.hashCode() ?: 0)
    override fun toString(): String = "Item($sensitivity, ${value.length} chars)"
}

/** A JSON schema the answer must conform to. [schema] is the schema document as JSON text. */
public data class JsonSchema(val name: String, val schema: String)

/** What a request needs from a provider. */
public data class Needs(
    /** Set automatically when a message carries an image. */
    val vision: Boolean = false,
    val structuredJson: JsonSchema? = null,
    /** Minimum context length in tokens; 0 for no requirement. */
    val minContext: Int = 0,
)

/** Which tier a request prefers or requires. [AppOrder] uses the order the app configured. */
public enum class Mode { AppOrder, PreferOnDevice, PreferLan, PreferCloud, OnlyOnDevice, OnlyCloud }

/** A soft latency target: a provider known to meet it is moved to the front of the plan. */
public data class Budget(val firstToken: Duration)

public data class Request(
    val messages: List<Message>,
    val needs: Needs = Needs(),
    val data: List<Item> = emptyList(),
    val mode: Mode = Mode.AppOrder,
    /** A provider the user chose explicitly. It is never replaced by another provider. */
    val pin: ProviderId? = null,
    /** Free-form task hint passed through to providers (for example `"title"`). */
    val task: String? = null,
    val budget: Budget? = null,
    /** Hard deadline for the whole request. Missing it yields [Result.NoVerdict]. */
    val deadline: Duration? = null,
    /**
     * Whether the request may be sent to a second provider after a first one may already have
     * processed it. Without this (or an [idempotencyKey]) the library falls back only after
     * failures that prove the request was not processed.
     */
    val safeToRetry: Boolean = false,
    val idempotencyKey: String? = null,
) {
    /** The `Personal` items; what an approval preview should show. */
    val personalItems: List<Item> get() = data.filter { it.sensitivity == Sensitivity.Personal }

    internal val replayable: Boolean get() = safeToRetry || idempotencyKey != null

    internal val needsVision: Boolean
        get() = needs.vision || messages.any { m -> m.parts.any { it is Part.Image } }
}

/**
 * What a provider actually receives. Built by the library after privacy rules have been applied.
 * It cannot hold an `Identifier` item: construction fails if one is passed.
 */
public class PreparedRequest(
    public val messages: List<Message>,
    public val items: List<Item> = emptyList(),
    public val needs: Needs = Needs(),
    public val task: String? = null,
    public val idempotencyKey: String? = null,
) {
    init {
        require(items.none { it.sensitivity == Sensitivity.Identifier }) {
            "Identifier items never reach a provider"
        }
    }

    /**
     * [messages] with [items] folded into the last user message as a leading context block.
     * Providers that have no separate channel for data items send this.
     */
    public fun renderedMessages(): List<Message> {
        if (items.isEmpty()) return messages
        val context = Part.Text(
            items.joinToString(separator = "\n", prefix = "Context:\n", postfix = "\n\n") { item ->
                if (item.label != null) "- ${item.label}: ${item.value}" else "- ${item.value}"
            }
        )
        val index = messages.indexOfLast { it.role == Role.User }
        if (index < 0) return messages + Message(Role.User, listOf(context))
        return messages.mapIndexed { i, m -> if (i == index) m.copy(parts = listOf(context) + m.parts) else m }
    }

    override fun toString(): String = "PreparedRequest(${messages.size} messages, ${items.size} items)"
}
