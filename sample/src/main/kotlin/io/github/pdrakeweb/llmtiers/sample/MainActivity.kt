package io.github.pdrakeweb.llmtiers.sample

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import io.github.pdrakeweb.llmtiers.Attempt
import io.github.pdrakeweb.llmtiers.Event
import io.github.pdrakeweb.llmtiers.Item
import io.github.pdrakeweb.llmtiers.Logger
import io.github.pdrakeweb.llmtiers.Mode
import io.github.pdrakeweb.llmtiers.Outcome
import io.github.pdrakeweb.llmtiers.ProviderId
import io.github.pdrakeweb.llmtiers.ProviderInfo
import io.github.pdrakeweb.llmtiers.Request
import io.github.pdrakeweb.llmtiers.Result
import io.github.pdrakeweb.llmtiers.Sensitivity
import io.github.pdrakeweb.llmtiers.Tier
import io.github.pdrakeweb.llmtiers.TieredAi
import io.github.pdrakeweb.llmtiers.android.ACCESS_LOCAL_NETWORK
import io.github.pdrakeweb.llmtiers.android.AndroidNetworkMonitor
import io.github.pdrakeweb.llmtiers.android.GeminiNano
import io.github.pdrakeweb.llmtiers.android.LiteRtModel
import io.github.pdrakeweb.llmtiers.android.needsLocalNetworkPermission
import io.github.pdrakeweb.llmtiers.android.privateCleartextOnly
import io.github.pdrakeweb.llmtiers.android.requiringLocalNetworkPermission
import io.github.pdrakeweb.llmtiers.modelmanager.ModelManager
import io.github.pdrakeweb.llmtiers.modelmanager.ModelSpec
import io.github.pdrakeweb.llmtiers.modelmanager.ModelState
import io.github.pdrakeweb.llmtiers.openai.OpenAiCompatible
import io.github.pdrakeweb.llmtiers.user
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

/**
 * A prompt box, the provider chain, and a log of where each request ran and why.
 *
 * Every field can also be set from launch extras (same names as [Config]'s properties, plus
 * `prompt`, `personal`, `autoApprove`, `autoSend` and `modelAction` = `download` or `delete`),
 * which is how the journey scripts drive it.
 * The cloud key is held in memory only.
 */
class MainActivity : Activity() {
    private data class Config(
        val lanUrl: String, val lanModel: String,
        val cloudUrl: String, val cloudModel: String, val cloudKey: String,
        val order: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var monitor: AndroidNetworkMonitor
    private val nano = GeminiNano()
    private lateinit var models: ModelManager
    private val local = LiteRtModel("local") { modelSpec()?.let { (models.state(it) as? ModelState.Ready)?.file } }
    private var watching: Job? = null
    private val http = OkHttpClient.Builder().privateCleartextOnly().build()

    private var built: Pair<Config, TieredAi>? = null
    private var running: Job? = null
    private var autoApprove = false

    private lateinit var lanUrl: EditText
    private lateinit var lanModel: EditText
    private lateinit var cloudUrl: EditText
    private lateinit var cloudModel: EditText
    private lateinit var cloudKey: EditText
    private lateinit var order: EditText
    private lateinit var mode: Spinner
    private lateinit var pin: Spinner
    private lateinit var prompt: EditText
    private lateinit var personal: EditText
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var log: TextView
    private lateinit var grant: Button
    private lateinit var modelUrl: EditText
    private lateinit var modelSha: EditText
    private lateinit var modelSize: EditText
    private lateinit var modelStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = AndroidNetworkMonitor(this)
        models = ModelManager(this)
        autoApprove = intent.getBooleanExtra("autoApprove", false)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 12f }.also(column::addView)
        fun field(name: String, hint: String, default: String = "", secret: Boolean = false): EditText {
            label(hint)
            return EditText(this).apply {
                setText(intent.getStringExtra(name) ?: default)
                contentDescription = name
                inputType = InputType.TYPE_CLASS_TEXT or
                    (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            }.also(column::addView)
        }
        fun spinner(name: String, hint: String, values: List<String>): Spinner {
            label(hint)
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(values.indexOf(intent.getStringExtra(name)).coerceAtLeast(0))
                contentDescription = name
            }.also(column::addView)
        }

        status = TextView(this).also(column::addView)
        grant = Button(this).apply {
            text = "Allow access to devices on the local network"
            setOnClickListener { requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), 1) }
        }.also(column::addView)

        lanUrl = field("lanUrl", "Home server URL (LAN), e.g. http://192.168.1.20:4000/v1")
        lanModel = field("lanModel", "Home server model")
        cloudUrl = field("cloudUrl", "Cloud URL", "https://openrouter.ai/api/v1")
        cloudModel = field("cloudModel", "Cloud model")
        cloudKey = field("cloudKey", "Cloud API key (kept in memory only)", secret = true)
        order = field("order", "Provider chain, in order (home, cloud, nano, local)", "home,cloud,nano,local")
        mode = spinner("mode", "Mode", Mode.entries.map { it.name })
        pin = spinner("pin", "Pin to one provider", listOf("(none)", "home", "cloud", "nano", "local"))
        prompt = field("prompt", "Prompt", "Say hello in five words.")
        personal = field("personal", "Personal note to send with it (asks before it leaves the device)")
        Button(this).apply {
            text = "Send"
            contentDescription = "send"
            setOnClickListener { send() }
        }.also(column::addView)
        answer = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }.also(column::addView)

        label("Downloaded on-device model (\"local\" in the chain)")
        modelUrl = field("modelUrl", "Model file URL (.litertlm)")
        modelSha = field("modelSha", "SHA-256 of the file")
        modelSize = field("modelSize", "Size in bytes")
        modelStatus = TextView(this).also(column::addView)
        Button(this).apply {
            text = "Download model (Wi-Fi only)"
            setOnClickListener { modelAction("download") }
        }.also(column::addView)
        Button(this).apply {
            text = "Delete model"
            setOnClickListener { modelAction("delete") }
        }.also(column::addView)

        label("Where each request ran, and why")
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }.also(column::addView)

        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        )
        column.post {
            when (val action = intent.getStringExtra("modelAction")) {
                null -> watchModel() // a cold start: pick up whatever state the model was left in
                else -> modelAction(action)
            }
        }
        if (intent.getBooleanExtra("autoSend", false)) column.post { send() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Health recorded while the permission was missing no longer applies.
        scope.launch { built?.second?.health?.reset(ProviderId("home")) }
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        monitor.close()
        nano.close()
        local.close()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val missing = needsLocalNetworkPermission(this)
        grant.visibility = if (missing) View.VISIBLE else View.GONE
        scope.launch {
            val network = monitor.state.value
            status.text = "Network: ${network.kind}   Gemini Nano: ${nano.status()}" +
                if (missing) "\nLocal network permission not granted: the home server is skipped." else ""
        }
    }

    private fun modelSpec(): ModelSpec? = runCatching {
        ModelSpec("local", modelUrl.text.toString().trim(), modelSha.text.toString().trim(), modelSize.text.toString().trim().toLong())
    }.getOrNull()

    private fun modelAction(action: String) {
        val spec = modelSpec()
        if (spec == null) {
            modelStatus.text = "Enter the model URL, its SHA-256 and its size first."
            return
        }
        scope.launch {
            if (action == "delete") models.delete(spec) else models.download(spec)
            watchModel()
        }
    }

    /** Shows the model's state and follows a download to its end. */
    private fun watchModel() {
        val spec = modelSpec() ?: return
        watching?.cancel()
        watching = scope.launch {
            models.observe(spec).collect { state ->
                modelStatus.text = when (state) {
                    ModelState.Missing -> "Not downloaded (${spec.sizeBytes / 1_000_000} MB)"
                    is ModelState.Downloading -> "Downloading ${state.bytesDownloaded / 1_000_000} of ${state.totalBytes / 1_000_000} MB"
                    is ModelState.Ready -> "Ready"
                    is ModelState.Failed -> "Failed: ${state.reason}" + if (state.recoverable) " (try again)" else ""
                }
                // For the journey script: state names and byte counts only.
                Log.i(TAG, "MODEL " + when (state) {
                    ModelState.Missing -> "Missing"
                    is ModelState.Downloading -> "Downloading bytes=${state.bytesDownloaded}"
                    is ModelState.Ready -> "Ready bytes=${state.file.length()}"
                    is ModelState.Failed -> "Failed reason=${state.reason} recoverable=${state.recoverable}"
                })
            }
        }
    }

    private fun config() = Config(
        lanUrl.text.toString().trim(), lanModel.text.toString().trim(),
        cloudUrl.text.toString().trim(), cloudModel.text.toString().trim(), cloudKey.text.toString().trim(),
        order.text.toString(),
    )

    private fun ai(config: Config): TieredAi {
        built?.let { (c, ai) -> if (c == config) return ai }
        val ids = config.order.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return TieredAi {
            for (id in ids) {
                when (id) {
                    "home" -> if (config.lanUrl.isNotEmpty()) {
                        provider(
                            OpenAiCompatible("home", config.lanUrl, config.lanModel, Tier.LAN, client = http)
                                .requiringLocalNetworkPermission(this@MainActivity)
                        )
                    }
                    "cloud" -> if (config.cloudUrl.isNotEmpty()) {
                        provider(
                            OpenAiCompatible(
                                "cloud", config.cloudUrl, config.cloudModel, Tier.CLOUD,
                                apiKey = config.cloudKey, requiresKey = config.cloudUrl.startsWith("https"), client = http,
                            )
                        )
                    }
                    "nano" -> provider(nano)
                    "local" -> provider(local)
                }
            }
            network = monitor
            approve { request, provider -> autoApprove || askApproval(request.personalItems, provider) }
            // Content-free by construction: provider ids, timings and failure types only.
            logger = Logger { Log.i(TAG, "${it.event} provider=${it.provider} tier=${it.tier} outcome=${it.outcome} ms=${it.elapsedMillis} firstTokenMs=${it.firstTokenMillis}") }
        }.also { built = config to it }
    }

    private suspend fun askApproval(items: List<Item>, provider: ProviderInfo): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this)
            .setTitle("Send to ${provider.id} (${provider.tier})?")
            .setMessage("This will leave the device:\n\n" + items.joinToString("\n") { "• ${it.value}" })
            .setPositiveButton("Send") { _, _ -> if (continuation.isActive) continuation.resume(true) }
            .setNegativeButton("Don't send") { _, _ -> if (continuation.isActive) continuation.resume(false) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
            .show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

    private fun send() {
        running?.cancel()
        val ai = ai(config())
        val note = personal.text.toString().trim()
        val request = Request(
            messages = listOf(user(prompt.text.toString())),
            data = if (note.isEmpty()) emptyList() else listOf(Item(note, Sensitivity.Personal, "note")),
            mode = Mode.valueOf(mode.selectedItem as String),
            pin = (pin.selectedItem as String).takeIf { it != "(none)" }?.let(::ProviderId),
            safeToRetry = true,
        )
        answer.text = ""
        val started = System.currentTimeMillis()
        running = scope.launch {
            ai.stream(request).collect { event ->
                when (event) {
                    is Event.Started -> Unit
                    is Event.Token -> answer.append(event.text)
                    is Event.FellBack -> {
                        if (event.discardPartial) answer.text = ""
                    }
                    is Event.Completed -> {
                        val took = System.currentTimeMillis() - started
                        report(event.result, took)
                        refreshStatus()
                    }
                }
            }
        }
    }

    private fun report(result: Result, tookMs: Long) {
        when (result) {
            is Result.Answer -> {
                answer.text = result.text + (result.incomplete?.let { "\n[cut short: $it]" } ?: "")
                lines(result.attempts)
                line("Answered by ${result.answeredBy} (${result.tier}) in $tookMs ms")
            }
            is Result.Answered -> answer.text = result.shortCircuit.toString()
            is Result.Blocked -> {
                answer.text = "Not sent."
                line("Blocked in $tookMs ms: ${result.reason}")
            }
            is Result.NoVerdict -> {
                lines(result.attempts)
                line("No verdict in $tookMs ms")
            }
            is Result.NoneAvailable -> {
                answer.text = "No AI available right now."
                lines(result.attempts)
                line("None available in $tookMs ms")
            }
        }
        // For the journey script. Provider ids, tiers, timings and failure types only.
        Log.i(TAG, "RESULT ${result::class.simpleName} by=${(result as? Result.Answer)?.answeredBy} tookMs=$tookMs")
    }

    /** The log is newest-first, so a request's attempts are added as one block, in order. */
    private fun lines(attempts: List<Attempt>) = line(
        attempts.joinToString("\n") { attempt ->
            "  ${attempt.provider} (${attempt.tier}): ${describe(attempt.outcome)}" +
                (attempt.firstTokenAfter?.let { ", first token after ${it.inWholeMilliseconds} ms" } ?: "") +
                (if (attempt.why.isNotEmpty()) " — ${attempt.why}" else "")
        }
    )

    private fun line(text: String) {
        log.text = "$text\n${log.text}"
    }

    private fun describe(outcome: Outcome): String = when (outcome) {
        Outcome.Succeeded -> "answered"
        Outcome.Escalated -> "escalated"
        is Outcome.Failed -> "failed: ${outcome.failure}"
        is Outcome.Skipped -> "skipped: ${outcome.reason.code}"
    }

    private companion object {
        const val TAG = "llmtiers-sample"
    }
}
