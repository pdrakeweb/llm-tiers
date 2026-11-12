package io.github.pdrakeweb.llmtiers.modelmanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ModelManagerTest {
    @TempDir lateinit var dir: File

    /** Plays the system download service: a test moves each download along by hand. */
    private class FakeDownloads : Downloads {
        class Entry(val url: String, val destination: File, val allowMetered: Boolean, var snapshot: DownloadSnapshot)

        val entries = mutableMapOf<Long, Entry>()
        private var next = 1L

        override fun enqueue(url: String, destination: File, allowMetered: Boolean, title: String): Long {
            entries[next] = Entry(url, destination, allowMetered, DownloadSnapshot(DownloadStatus.Pending, 0, -1))
            return next++
        }

        override fun query(id: Long) = entries[id]?.snapshot
        override fun remove(id: Long) { entries.remove(id)?.destination?.delete() }

        fun progress(bytes: ByteArray, total: Long) {
            val e = entries.values.last()
            e.destination.writeBytes(bytes)
            e.snapshot = DownloadSnapshot(DownloadStatus.Running, bytes.size.toLong(), total)
        }

        fun complete(bytes: ByteArray) {
            val e = entries.values.last()
            e.destination.writeBytes(bytes)
            e.snapshot = DownloadSnapshot(DownloadStatus.Successful, bytes.size.toLong(), bytes.size.toLong())
        }

        fun fail() { entries.values.last().snapshot = DownloadSnapshot(DownloadStatus.Failed, 10, 100) }
    }

    private class FakeStore : KeyValues {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private val downloads = FakeDownloads()
    private val store = FakeStore()
    private var free = Long.MAX_VALUE
    private val model = ByteArray(5000) { (it * 31 % 251).toByte() }
    private val spec = ModelSpec("gemma-text", "https://example.test/gemma.litertlm", sha256(model), model.size.toLong())

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A new instance over the same disk and download service: what the app has after being killed. */
    private var memory = Long.MAX_VALUE
    private fun manager() = ModelManager(dir, downloads, store, Dispatchers.Unconfined, freeSpace = { free }, totalMemory = { memory })

    @Test
    fun `Missing, then Downloading with progress, then Ready once the checksum matches`() = runBlocking<Unit> {
        val m = manager()
        assertEquals(ModelState.Missing, m.state(spec))

        assertEquals(ModelState.Downloading(0, 5000), m.download(spec))
        assertEquals("https://example.test/gemma.litertlm", downloads.entries.values.single().url)
        assertEquals(ModelState.Downloading(0, 5000), m.state(spec), "pending, total not yet known")

        downloads.progress(model.copyOf(2000), total = 5000)
        assertEquals(ModelState.Downloading(2000, 5000), m.state(spec))

        downloads.complete(model)
        val ready = assertIs<ModelState.Ready>(m.state(spec))
        assertEquals(m.file(spec), ready.file)
        assertTrue(ready.file.readBytes().contentEquals(model))
        assertEquals(ready, m.state(spec), "stays Ready")
    }

    @Test
    fun `downloads wait for an unmetered connection unless the app says otherwise`() = runBlocking<Unit> {
        manager().download(spec)
        assertFalse(downloads.entries.values.last().allowMetered)
        manager().delete(spec)
        manager().download(spec, allowMetered = true)
        assertTrue(downloads.entries.values.last().allowMetered)
    }

    @Test
    fun `a download survives the app being killed - a new instance picks it up and finishes it`() = runBlocking<Unit> {
        manager().download(spec)
        downloads.progress(model.copyOf(1000), total = 5000)

        // Process death: nothing in memory survives. Only the store and the download service do.
        val afterRestart = manager()
        assertEquals(ModelState.Downloading(1000, 5000), afterRestart.state(spec))
        assertEquals(ModelState.Downloading(1000, 5000), afterRestart.download(spec), "asking again does not start a second download")
        assertEquals(1, downloads.entries.size)

        downloads.complete(model)
        assertIs<ModelState.Ready>(manager().state(spec))
        assertIs<ModelState.Ready>(manager().state(spec), "and still Ready after yet another restart")
    }

    @Test
    fun `a checksum mismatch is reported, the file is deleted, and it is not called recoverable`() = runBlocking<Unit> {
        val m = manager()
        m.download(spec)
        val tampered = model.copyOf().also { it[100] = (it[100] + 1).toByte() }
        downloads.complete(tampered)

        assertEquals(ModelState.Failed(FailureReason.ChecksumMismatch, recoverable = false), m.state(spec))
        assertFalse(m.file(spec).exists(), "an unverified file never stays on disk")
        assertEquals(ModelState.Failed(FailureReason.ChecksumMismatch, recoverable = false), manager().state(spec), "still reported after a restart")

        m.download(spec)
        downloads.complete(model)
        assertIs<ModelState.Ready>(m.state(spec), "a fresh download clears the failure")
    }

    @Test
    fun `a file of the wrong size is a SizeMismatch`() = runBlocking<Unit> {
        val m = manager()
        m.download(spec)
        downloads.complete(model.copyOf(4999))
        assertEquals(ModelState.Failed(FailureReason.SizeMismatch, recoverable = false), m.state(spec))
        assertFalse(m.file(spec).exists())
    }

    @Test
    fun `a failed download is recoverable and can be retried`() = runBlocking<Unit> {
        val m = manager()
        m.download(spec)
        downloads.fail()
        assertEquals(ModelState.Failed(FailureReason.DownloadFailed, recoverable = true), m.state(spec))
        assertEquals(ModelState.Downloading(0, 5000), m.download(spec))
    }

    @Test
    fun `not enough free space fails before anything is downloaded`() = runBlocking<Unit> {
        free = 4999
        assertEquals(ModelState.Failed(FailureReason.InsufficientSpace, recoverable = true), manager().download(spec))
        assertTrue(downloads.entries.isEmpty())
    }

    @Test
    fun `the model is deletable, mid-download or when ready`() = runBlocking<Unit> {
        val m = manager()
        m.download(spec)
        downloads.progress(model.copyOf(100), total = 5000)
        m.delete(spec)
        assertEquals(ModelState.Missing, m.state(spec))
        assertTrue(downloads.entries.isEmpty(), "the download was cancelled")

        m.download(spec)
        downloads.complete(model)
        assertIs<ModelState.Ready>(m.state(spec))
        m.delete(spec)
        assertEquals(ModelState.Missing, m.state(spec))
        assertFalse(m.file(spec).exists())
        assertTrue(store.map.isEmpty(), "nothing is left behind")
    }

    @Test
    fun `on a cold start a Ready file whose size changed is discarded`() = runBlocking<Unit> {
        val m = manager()
        m.download(spec)
        downloads.complete(model)
        assertIs<ModelState.Ready>(m.state(spec))

        m.file(spec).writeBytes(model.copyOf(3000)) // truncated behind our back
        assertEquals(ModelState.Missing, manager().state(spec))
        assertFalse(m.file(spec).exists())
    }

    @Test
    fun `a file that was never verified by this library is not trusted`() = runBlocking<Unit> {
        val m = manager()
        dir.mkdirs()
        m.file(spec).writeBytes(model) // right bytes, but nothing verified them
        assertEquals(ModelState.Missing, m.state(spec))
    }

    @Test
    fun `observe emits each change and stops at a terminal state`() = runBlocking<Unit> {
        val m = manager()
        m.download(spec)
        downloads.complete(model)
        assertIs<ModelState.Ready>(m.observe(spec, 1.milliseconds).toList().single())
        assertEquals(listOf<ModelState>(ModelState.Missing), manager().also { it.delete(spec) }.observe(spec, 1.milliseconds).toList())
    }

    @Test
    fun `a spec must have a safe id, a real SHA-256 and a size`() {
        val sha = "a".repeat(64)
        assertFailsWith<IllegalArgumentException> { ModelSpec("../escape", "u", sha, 1) }
        assertFailsWith<IllegalArgumentException> { ModelSpec(".hidden", "u", sha, 1) }
        assertFailsWith<IllegalArgumentException> { ModelSpec("ok", "u", "abc", 1) }
        assertFailsWith<IllegalArgumentException> { ModelSpec("ok", "u", sha, 0) }
        ModelSpec("gemma-3n_E2B.q4", "u", sha.uppercase(), 1)
    }

    @Test
    fun `the file keeps its URL's extension, because runtimes identify the model format by it`() {
        val m = manager()
        val sha = "a".repeat(64)
        assertEquals("gemma-text.litertlm", m.file(spec).name)
        assertEquals("a.litertlm", m.file(ModelSpec("a", "https://h/x/Model.LITERTLM?download=true#frag", sha, 1)).name)
        assertEquals("b.task", m.file(ModelSpec("b", "https://h/model.task", sha, 1)).name)
        assertEquals("c.model", m.file(ModelSpec("c", "https://h/download", sha, 1)).name, "no extension")
        assertEquals("d.model", m.file(ModelSpec("d", "https://h/a.b/weird.ex-t", sha, 1)).name, "not a plain extension")
    }

    @Test
    fun `fit advises on memory and storage for a model picker, and never blocks a model already downloaded`() = runBlocking<Unit> {
        val m = manager()
        assertEquals(ModelFit.Ok, m.fit(spec))

        memory = spec.sizeBytes * 2 - 1
        assertEquals(ModelFit.LowMemory, m.fit(spec), "less than twice the file size")
        memory = spec.sizeBytes * 2
        assertEquals(ModelFit.Ok, m.fit(spec))

        free = spec.sizeBytes - 1
        assertEquals(ModelFit.NotEnoughStorage, m.fit(spec))
        free = Long.MAX_VALUE
        m.download(spec)
        downloads.complete(model)
        m.state(spec)
        free = 0
        assertEquals(ModelFit.Ok, m.fit(spec), "it is already on the device")
    }

    @Test
    fun `two models can be on the device at once and are managed independently`() = runBlocking<Unit> {
        val other = ModelSpec("second", "https://example.test/second.litertlm", spec.sha256, spec.sizeBytes, displayName = "Second", contextTokens = 8192)
        val m = manager()
        m.download(spec)
        downloads.complete(model)
        assertIs<ModelState.Ready>(m.state(spec))
        assertEquals(ModelState.Missing, m.state(other))

        m.download(other)
        downloads.complete(model)
        assertIs<ModelState.Ready>(m.state(other))
        m.delete(spec)
        assertEquals(ModelState.Missing, m.state(spec))
        assertIs<ModelState.Ready>(m.state(other), "deleting one leaves the other")
    }

    @Test
    fun `the starter catalog is well formed - pinned revisions, distinct ids, smallest first`() {
        val all = OpenModels.all
        assertTrue(all.size >= 3)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.sortedBy { it.sizeBytes }, all)
        for (s in all) {
            assertTrue(s.url.startsWith("https://"), s.id)
            assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(s.url), "${s.id} is pinned to an exact revision")
            assertTrue(s.url.endsWith(".litertlm") && s.displayName != s.id && s.contextTokens >= 1024, s.id)
        }
    }
}
