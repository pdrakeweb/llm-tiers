package io.github.pdrakeweb.llmtiers.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.pdrakeweb.llmtiers.NetworkId
import io.github.pdrakeweb.llmtiers.NetworkKind
import io.github.pdrakeweb.llmtiers.NetworkMonitor
import io.github.pdrakeweb.llmtiers.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Reports the device's default network. Each attachment gets its own id (the Android `Network`
 * handle), so provider health recorded on one network is not carried to the next: coming home
 * makes the LAN provider eligible again without a restart.
 *
 * Needs `ACCESS_NETWORK_STATE`, which this library's manifest declares. Call [close] when the
 * owner goes away; an application-scoped instance can simply live for the process.
 */
public class AndroidNetworkMonitor(context: Context) : NetworkMonitor, AutoCloseable {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(current())
    override val state: StateFlow<NetworkState> get() = _state

    @Volatile private var wifi: Network? = null

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _state.value = stateOf(network, capabilities)
        }

        override fun onLost(network: Network) {
            if (_state.value.id == idOf(network)) _state.value = NetworkState.Offline
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wifi = network }
        override fun onLost(network: Network) { if (wifi == network) wifi = null }
    }

    init {
        connectivity.registerDefaultNetworkCallback(defaultCallback)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiCallback,
        )
    }

    /**
     * A socket factory bound to the Wi-Fi network, for LAN providers:
     * `OkHttpClient.Builder().socketFactory(monitor.wifiSocketFactory())`. Connections fail with
     * an `IOException` when there is no Wi-Fi, instead of quietly going out over cellular.
     */
    public fun wifiSocketFactory(): SocketFactory = object : SocketFactory() {
        private fun delegate(): SocketFactory = wifi?.socketFactory ?: throw IOException("No Wi-Fi network")
        override fun createSocket(): Socket = delegate().createSocket()
        override fun createSocket(host: String, port: Int): Socket = delegate().createSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            delegate().createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = delegate().createSocket(host, port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            delegate().createSocket(address, port, localAddress, localPort)
    }

    override fun close() {
        connectivity.unregisterNetworkCallback(defaultCallback)
        connectivity.unregisterNetworkCallback(wifiCallback)
    }

    private fun current(): NetworkState {
        val network = connectivity.activeNetwork ?: return NetworkState.Offline
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return NetworkState.Offline
        return stateOf(network, capabilities)
    }

    private fun idOf(network: Network) = NetworkId("net:${network.networkHandle}")

    private fun stateOf(network: Network, capabilities: NetworkCapabilities): NetworkState = NetworkState(
        id = idOf(network),
        kind = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
            else -> NetworkKind.Other
        },
    )
}
