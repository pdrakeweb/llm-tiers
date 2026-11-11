package io.github.pdrakeweb.llmtiers.modelmanager

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * An on-device model an app can download.
 *
 * @property id a file-name-safe identifier (letters, digits, `.`, `_`, `-`).
 * @property sha256 the expected SHA-256 of the file, as hex.
 * @property sizeBytes the exact file size. Show it to the user before downloading.
 */
public data class ModelSpec(val id: String, val url: String, val sha256: String, val sizeBytes: Long) {
    init {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it in "._-" } && !id.startsWith('.')) { "id must be file-name safe" }
        require(sha256.length == 64 && sha256.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "sha256 must be 64 hex characters" }
        require(sizeBytes > 0) { "sizeBytes must be positive" }
    }
}

public enum class FailureReason {
    /** The network download failed. Trying again may work. */
    DownloadFailed,

    /** Not enough free space on the device. */
    InsufficientSpace,

    /** The downloaded file is not the size the spec promises. The file was deleted. */
    SizeMismatch,

    /** The downloaded file does not have the expected SHA-256. The file was deleted. */
    ChecksumMismatch,
}

/** `Missing → Downloading → Ready | Failed`. */
public sealed interface ModelState {
    public data object Missing : ModelState
    public data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ModelState

    /** Downloaded, the right size, and SHA-256 verified. */
    public data class Ready(val file: File) : ModelState

    /** [recoverable] is true when simply downloading again may succeed. */
    public data class Failed(val reason: FailureReason, val recoverable: Boolean) : ModelState
}

internal enum class DownloadStatus { Pending, Running, Paused, Successful, Failed }

internal data class DownloadSnapshot(val status: DownloadStatus, val bytes: Long, val total: Long)

/** The system download service, behind an interface so the state machine can be tested on the JVM. */
internal interface Downloads {
    fun enqueue(url: String, destination: File, allowMetered: Boolean, title: String): Long
    fun query(id: Long): DownloadSnapshot?
    fun remove(id: Long)
}

/** Small persistent key-value store (SharedPreferences on a device). */
internal interface KeyValues {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Downloads, verifies and deletes on-device models.
 *
 * Downloads run in Android's system `DownloadManager`, not in the app's process, so they continue
 * when the user leaves the screen or the app is killed, and they resume after a dropped connection.
 * Everything this class knows is rebuilt from disk and from the download service, so a new
 * instance after a restart picks up exactly where the old one left off. By default a download
 * waits for an unmetered connection (Wi-Fi).
 */
public class ModelManager internal constructor(
    private val directory: File,
    private val downloads: Downloads,
    private val store: KeyValues,
    private val io: CoroutineDispatcher,
    private val freeSpace: () -> Long = { directory.usableSpace },
) {
    /**
     * Models are kept in the app's own external files directory, which needs no storage
     * permission and is removed when the app is uninstalled.
     */
    public constructor(context: Context) : this(
        directory = File(
            checkNotNull(context.applicationContext.getExternalFilesDir(null)) { "External storage is not available" },
            "llm-tiers-models",
        ),
        downloads = SystemDownloads(context.applicationContext),
        store = PreferenceKeyValues(context.applicationContext),
        io = Dispatchers.IO,
    )

    private val mutex = Mutex()

    /** Where the model is, or will be, stored. Use [state] to know whether it is ready. */
    public fun file(spec: ModelSpec): File = File(directory, "${spec.id}.model")

    /**
     * The model's current state. Cheap unless a download has just finished, in which case this
     * call verifies its size and SHA-256. Call it on every cold start: a `Ready` file whose size
     * no longer matches is discarded.
     */
    public suspend fun state(spec: ModelSpec): ModelState = mutex.withLock { withContext(io) { evaluate(spec) } }

    /** Polls [state] until the model is `Ready`, `Failed` or `Missing`, emitting each change. */
    public fun observe(spec: ModelSpec, interval: Duration = 500.milliseconds): Flow<ModelState> = flow {
        while (true) {
            val state = state(spec)
            emit(state)
            if (state !is ModelState.Downloading) break
            delay(interval)
        }
    }.distinctUntilChanged()

    /**
     * Starts the download unless the model is already ready or downloading.
     * @param allowMetered let the download use a metered connection such as cellular data.
     */
    public suspend fun download(spec: ModelSpec, allowMetered: Boolean = false): ModelState = mutex.withLock {
        withContext(io) {
            store.put(failureKey(spec), null)
            val current = evaluate(spec)
            if (current is ModelState.Ready || current is ModelState.Downloading) return@withContext current
            directory.mkdirs()
            if (freeSpace() < spec.sizeBytes) return@withContext ModelState.Failed(FailureReason.InsufficientSpace, recoverable = true)
            file(spec).delete()
            store.put(failureKey(spec), null)
            val id = downloads.enqueue(spec.url, file(spec), allowMetered, title = spec.id)
            store.put(downloadKey(spec), id.toString())
            ModelState.Downloading(0, spec.sizeBytes)
        }
    }

    /** Cancels any download and removes the model from the device. */
    public suspend fun delete(spec: ModelSpec): Unit = mutex.withLock {
        withContext(io) {
            store.get(downloadKey(spec))?.toLongOrNull()?.let(downloads::remove)
            store.put(downloadKey(spec), null)
            store.put(verifiedKey(spec), null)
            store.put(failureKey(spec), null)
            file(spec).delete()
        }
    }

    private fun evaluate(spec: ModelSpec): ModelState {
        val file = file(spec)
        val downloadId = store.get(downloadKey(spec))?.toLongOrNull()
        if (downloadId != null) {
            val snapshot = downloads.query(downloadId)
            when (snapshot?.status) {
                DownloadStatus.Pending, DownloadStatus.Running, DownloadStatus.Paused ->
                    return ModelState.Downloading(snapshot.bytes.coerceAtLeast(0), if (snapshot.total > 0) snapshot.total else spec.sizeBytes)
                DownloadStatus.Failed -> {
                    downloads.remove(downloadId)
                    store.put(downloadKey(spec), null)
                    return fail(spec, FailureReason.DownloadFailed)
                }
                DownloadStatus.Successful -> {
                    store.put(downloadKey(spec), null)
                    return verify(spec)
                }
                null -> store.put(downloadKey(spec), null) // the download service forgot it; judge by the file
            }
        }
        store.get(failureKey(spec))?.let { name ->
            FailureReason.entries.firstOrNull { it.name == name }?.let { return failed(it) }
        }
        if (!file.exists()) return ModelState.Missing
        // Trust only a file this class verified, and only while its size still matches.
        if (store.get(verifiedKey(spec)) == stamp(spec) && file.length() == spec.sizeBytes) return ModelState.Ready(file)
        file.delete()
        store.put(verifiedKey(spec), null)
        return ModelState.Missing
    }

    private fun verify(spec: ModelSpec): ModelState {
        val file = file(spec)
        if (!file.exists()) return fail(spec, FailureReason.DownloadFailed)
        if (file.length() != spec.sizeBytes) return fail(spec, FailureReason.SizeMismatch)
        if (!sha256(file).equals(spec.sha256, ignoreCase = true)) return fail(spec, FailureReason.ChecksumMismatch)
        store.put(verifiedKey(spec), stamp(spec))
        return ModelState.Ready(file)
    }

    /** A failed file never stays on disk, and the failure is remembered until the next download or delete. */
    private fun fail(spec: ModelSpec, reason: FailureReason): ModelState {
        file(spec).delete()
        store.put(verifiedKey(spec), null)
        store.put(failureKey(spec), reason.name)
        return failed(reason)
    }

    private fun failed(reason: FailureReason) = ModelState.Failed(
        reason,
        recoverable = reason == FailureReason.DownloadFailed || reason == FailureReason.InsufficientSpace,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stamp(spec: ModelSpec) = "${spec.sha256.lowercase()}:${spec.sizeBytes}"
    private fun downloadKey(spec: ModelSpec) = "download:${spec.id}"
    private fun verifiedKey(spec: ModelSpec) = "verified:${spec.id}"
    private fun failureKey(spec: ModelSpec) = "failure:${spec.id}"
}
