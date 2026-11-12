package io.github.pdrakeweb.llmtiers.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.pdrakeweb.llmtiers.Capabilities
import io.github.pdrakeweb.llmtiers.PreparedRequest
import io.github.pdrakeweb.llmtiers.Provider
import io.github.pdrakeweb.llmtiers.ProviderEvent
import io.github.pdrakeweb.llmtiers.ProviderFailure
import io.github.pdrakeweb.llmtiers.Reachability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The runtime permission Android 17 (API 37) requires before an app that targets 37 or higher may
 * talk to addresses on the local network. It belongs to the "Nearby devices" permission group.
 * An app declares it in its manifest and requests it from the screen where the user configures a
 * LAN server.
 */
public const val ACCESS_LOCAL_NETWORK: String = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * True when this app must hold [ACCESS_LOCAL_NETWORK] to reach a LAN server and does not. Always
 * false below Android 17 or when the app targets an earlier SDK, where local network access comes
 * with the `INTERNET` permission.
 */
public fun needsLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 37 &&
        context.applicationInfo.targetSdkVersion >= 37 &&
        context.checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

/**
 * Wraps a LAN provider so that, while the local-network permission is missing, it reports
 * `LocalNetworkPermissionDenied` at once instead of timing out: without the permission Android
 * drops the connection silently. The router then falls through cleanly, and the result tells the
 * app exactly what to ask the user for.
 */
public fun Provider.requiringLocalNetworkPermission(context: Context): Provider {
    val app = context.applicationContext
    return LocalNetworkGate(this) { needsLocalNetworkPermission(app) }
}

internal class LocalNetworkGate(private val delegate: Provider, private val permissionMissing: () -> Boolean) : Provider by delegate {
    override suspend fun capabilities(): Capabilities = delegate.capabilities()

    override suspend fun probe(): Reachability =
        if (permissionMissing()) Reachability.Unreachable(ProviderFailure.LocalNetworkPermissionDenied) else delegate.probe()

    override fun stream(request: PreparedRequest): Flow<ProviderEvent> =
        if (permissionMissing()) flowOf(ProviderEvent.Failed(ProviderFailure.LocalNetworkPermissionDenied)) else delegate.stream(request)
}
