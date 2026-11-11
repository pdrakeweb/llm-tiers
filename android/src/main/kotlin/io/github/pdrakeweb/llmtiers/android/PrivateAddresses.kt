package io.github.pdrakeweb.llmtiers.android

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy

/**
 * Which addresses count as "on this device or this local network". Plain HTTP is allowed only to
 * these; everything else must be HTTPS.
 */
public object PrivateAddresses {
    /** Loopback, link-local, RFC 1918 private, CGNAT (100.64.0.0/10) or IPv6 unique-local (fc00::/7). */
    public fun isPrivate(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isPrivateV4(bytes[0].toInt() and 0xff, bytes[1].toInt() and 0xff)
            is Inet6Address -> when {
                (bytes[0].toInt() and 0xfe) == 0xfc -> true // fc00::/7 unique local
                isV4Mapped(bytes) -> isPrivateV4(bytes[12].toInt() and 0xff, bytes[13].toInt() and 0xff) ||
                    (bytes[12].toInt() and 0xff) == 127
                else -> false
            }
            else -> false
        }
    }

    /** A multicast-DNS name: by definition a host on the local link. */
    public fun isLocalName(host: String): Boolean = host.trimEnd('.').endsWith(".local", ignoreCase = true)

    /**
     * The cleartext rule. [address] is the address the connection actually reached (after DNS
     * resolution), [direct] is false when the connection goes through a proxy.
     */
    public fun allowsCleartext(host: String, address: InetAddress?, direct: Boolean): Boolean =
        direct && address != null && (isPrivate(address) || isLocalName(host))

    private fun isPrivateV4(a: Int, b: Int): Boolean = when {
        a == 10 -> true
        a == 172 && b in 16..31 -> true
        a == 192 && b == 168 -> true
        a == 100 && b in 64..127 -> true // CGNAT, used by overlay networks such as Tailscale
        else -> false
    }

    private fun isV4Mapped(b: ByteArray): Boolean =
        (0..9).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
}

/** Thrown before any request bytes are written when plain HTTP would go to a public address. */
public class CleartextNotAllowedException(host: String) :
    IOException("Plain HTTP is only allowed to private or local addresses; use HTTPS for $host")

/**
 * Enforces the cleartext rule on the address OkHttp actually connected to, so a hostname that
 * resolves somewhere public (or is re-pointed later) cannot receive a plain-HTTP request. It runs
 * as a network interceptor: after the connection is chosen, before the request is written.
 */
public class PrivateCleartextInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.isHttps) {
            val route = chain.connection()?.route()
            val allowed = PrivateAddresses.allowsCleartext(
                host = request.url.host,
                address = route?.socketAddress?.address,
                direct = route?.proxy?.type() == Proxy.Type.DIRECT,
            )
            if (!allowed) throw CleartextNotAllowedException(request.url.host)
        }
        return chain.proceed(request)
    }
}

/**
 * Makes this client refuse plain HTTP to anything but a private or local address. Pass the built
 * client to LAN providers. The app must still permit cleartext in its network security config;
 * see docs/ANDROID.md.
 */
public fun OkHttpClient.Builder.privateCleartextOnly(): OkHttpClient.Builder = addNetworkInterceptor(PrivateCleartextInterceptor())
