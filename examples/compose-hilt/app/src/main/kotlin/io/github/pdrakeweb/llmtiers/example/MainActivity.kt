package io.github.pdrakeweb.llmtiers.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pdrakeweb.llmtiers.Mode
import io.github.pdrakeweb.llmtiers.Strategy
import io.github.pdrakeweb.llmtiers.android.ACCESS_LOCAL_NETWORK
import io.github.pdrakeweb.llmtiers.android.needsLocalNetworkPermission
import io.github.pdrakeweb.llmtiers.modelmanager.ModelFit
import io.github.pdrakeweb.llmtiers.modelmanager.ModelManager
import io.github.pdrakeweb.llmtiers.modelmanager.ModelState
import io.github.pdrakeweb.llmtiers.modelmanager.OpenModels
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings and the on-device model download, kept apart from the chat so each stays small. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val store: SettingsStore,
    private val gateway: AiGateway,
    private val models: ModelManager,
    val approvals: ApprovalBroker,
) : ViewModel() {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<AiSettings> = _settings
    private val _model = MutableStateFlow("")
    val model: StateFlow<String> = _model
    private var watching: Job? = null

    init {
        watchModel() // on every cold start: pick up a download that carried on without the app
    }

    fun save(settings: AiSettings) {
        store.save(settings)
        _settings.value = store.load()
        watchModel()
    }

    fun needsLocalNetworkPermission(): Boolean =
        isHomeNetworkUrl(_settings.value.remoteUrl) && needsLocalNetworkPermission(context)

    fun localNetworkPermissionAnswered() {
        viewModelScope.launch { gateway.forgetRemoteHealth() }
    }

    fun downloadModel() = modelAction { models.download(it) }
    fun deleteModel() = modelAction { models.delete(it) }

    private fun modelAction(action: suspend (io.github.pdrakeweb.llmtiers.modelmanager.ModelSpec) -> Unit) {
        val spec = _settings.value.model() ?: return
        viewModelScope.launch {
            action(spec)
            watchModel()
        }
    }

    private fun watchModel() {
        watching?.cancel()
        val spec = _settings.value.model()
        if (spec == null) {
            _model.value = "No on-device model chosen."
            return
        }
        watching = viewModelScope.launch {
            val advice = when (models.fit(spec)) {
                ModelFit.Ok -> ""
                ModelFit.LowMemory -> " This phone has little memory for a model this size."
                ModelFit.NotEnoughStorage -> " Not enough free storage to download it."
            }
            models.observe(spec).collect { state ->
                _model.value = when (state) {
                    ModelState.Missing -> "Not downloaded (${spec.sizeBytes / 1_000_000} MB)."
                    is ModelState.Downloading -> "Downloading ${state.bytesDownloaded / 1_000_000} of ${state.totalBytes / 1_000_000} MB…"
                    is ModelState.Ready -> "Downloaded."
                    is ModelState.Failed -> "Failed: ${state.reason}." + if (state.recoverable) " Try again." else ""
                } + advice
                if (state is ModelState.Ready) {
                    // Load it now so the first question does not pay for it.
                    _model.value = gateway.warmUpLocalModel()?.let { "Downloaded, but it could not be loaded: $it" } ?: "Ready."
                }
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { Screen(chat, settings) } } }
    }
}

@Composable
private fun Screen(chat: ChatViewModel, settings: SettingsViewModel) {
    val state by chat.state.collectAsStateWithLifecycle()
    val saved by settings.settings.collectAsStateWithLifecycle()
    val modelStatus by settings.model.collectAsStateWithLifecycle()
    val approval by settings.approvals.pending.collectAsStateWithLifecycle()

    var url by rememberSaveable { mutableStateOf(saved.remoteUrl) }
    var model by rememberSaveable { mutableStateOf(saved.remoteModel) }
    var key by rememberSaveable { mutableStateOf(saved.apiKey.orEmpty()) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var needsPermission by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saved) { needsPermission = settings.needsLocalNetworkPermission() }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        settings.localNetworkPermissionAnswered()
        needsPermission = settings.needsLocalNetworkPermission()
    }

    Column(
        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Remote provider", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("OpenAI-compatible URL, ending in /v1") }, singleLine = true)
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model name") }, singleLine = true)
        OutlinedTextField(
            key, { key = it },
            // contentType lets a password manager offer to fill and save the key.
            Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            label = { Text("API key (stored encrypted on this device)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button({ settings.save(saved.copy(remoteUrl = url, remoteModel = model, apiKey = key)) }) { Text("Save") }
        if (needsPermission) {
            // Asked here, where the user has just entered a home-network address, not at startup.
            OutlinedButton({ askPermission.launch(ACCESS_LOCAL_NETWORK) }) { Text("Allow access to devices on your network") }
        }

        Text("On-device model", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (spec in OpenModels.all.take(2)) {
                val chosen = saved.modelId == spec.id
                OutlinedButton({ settings.save(saved.copy(modelId = if (chosen) null else spec.id)) }) {
                    Text((if (chosen) "✓ " else "") + spec.displayName.substringBefore(" ("))
                }
            }
        }
        Text(modelStatus)
        if (saved.modelId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(settings::downloadModel) { Text("Download (Wi-Fi)") }
                TextButton(settings::deleteModel) { Text("Delete") }
            }
        }

        Text("Ask", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Question") })
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Personal note (asks before it leaves the device)") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ chat.send(prompt, note) }, enabled = prompt.isNotBlank()) { Text("Send") }
            OutlinedButton({ chat.send(prompt, note, mode = Mode.OnlyOnDevice) }, enabled = prompt.isNotBlank()) { Text("On device") }
            OutlinedButton({ chat.send(prompt, note, strategy = Strategy.FastThenBest) }, enabled = prompt.isNotBlank()) { Text("Quick, then best") }
            if (state.running) TextButton(chat::stop) { Text("Stop") }
        }
        if (state.thinking) Text("Thinking…")
        if (state.running && state.answer.isEmpty() && !state.thinking) Text("Working…")
        Text(state.answer, style = MaterialTheme.typography.bodyLarge)
        Text(state.status, style = MaterialTheme.typography.labelLarge)
        state.trail.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    // The preview shows exactly what would leave the device, and where it would go.
    approval?.let { question ->
        AlertDialog(
            onDismissRequest = { question.answer(false) },
            title = { Text("Send to ${question.provider}?") },
            text = { Text("This will leave your device:\n\n" + question.items.joinToString("\n") { "• $it" }) },
            confirmButton = { TextButton({ question.answer(true) }) { Text("Send") } },
            dismissButton = { TextButton({ question.answer(false) }) { Text("Don't send") } },
        )
    }
}
