package io.github.pdrakeweb.llmtiers.testing

import io.github.pdrakeweb.llmtiers.Clock
import io.github.pdrakeweb.llmtiers.LogRecord
import io.github.pdrakeweb.llmtiers.Logger
import io.github.pdrakeweb.llmtiers.NetworkId
import io.github.pdrakeweb.llmtiers.NetworkKind
import io.github.pdrakeweb.llmtiers.NetworkMonitor
import io.github.pdrakeweb.llmtiers.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Duration

/** A clock that only moves when told to. */
public class FakeClock(private var millis: Long = 0) : Clock {
    override fun nowMillis(): Long = millis
    public fun advance(by: Duration) { millis += by.inWholeMilliseconds }
}

/** A clock that follows `runTest` virtual time, so timeouts and health windows stay in step. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
public class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun nowMillis(): Long = scheduler.currentTime
}

/** A network monitor a test drives by hand: home Wi-Fi, away on cellular, offline. */
public class ScriptedNetworkMonitor(initial: NetworkState = wifi("home")) : NetworkMonitor {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<NetworkState> get() = _state

    public fun switchTo(state: NetworkState) { _state.value = state }
    public fun goOffline() { _state.value = NetworkState.Offline }

    public companion object {
        public fun wifi(name: String): NetworkState = NetworkState(NetworkId("wifi:$name"), NetworkKind.Wifi)
        public fun cellular(name: String = "carrier"): NetworkState = NetworkState(NetworkId("cell:$name"), NetworkKind.Cellular)
    }
}

/** Collects log records so a test can assert nothing sensitive was logged. */
public class RecordingLogger : Logger {
    private val _records = mutableListOf<LogRecord>()
    public val records: List<LogRecord> get() = synchronized(_records) { _records.toList() }
    override fun log(record: LogRecord) { synchronized(_records) { _records += record } }
}
