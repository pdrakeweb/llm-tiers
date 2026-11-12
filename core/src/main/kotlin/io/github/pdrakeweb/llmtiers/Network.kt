package io.github.pdrakeweb.llmtiers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Identifies one network attachment. Health is tracked per provider and per network. */
@JvmInline
public value class NetworkId(public val value: String) {
    override fun toString(): String = value
}

public enum class NetworkKind { None, Wifi, Cellular, Ethernet, Other }

public data class NetworkState(val id: NetworkId, val kind: NetworkKind) {
    val online: Boolean get() = kind != NetworkKind.None

    public companion object {
        /** Used when the app supplies no monitor: assumed online, one unnamed network. */
        public val Unknown: NetworkState = NetworkState(NetworkId("unknown"), NetworkKind.Other)
        public val Offline: NetworkState = NetworkState(NetworkId("none"), NetworkKind.None)
    }
}

/** Reports the current network. The Android implementation lives in the `:android` module. */
public interface NetworkMonitor {
    public val state: StateFlow<NetworkState>

    public companion object {
        public fun fixed(state: NetworkState = NetworkState.Unknown): NetworkMonitor = object : NetworkMonitor {
            override val state: StateFlow<NetworkState> = MutableStateFlow(state)
        }
    }
}
