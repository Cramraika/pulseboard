package com.pulseboard.core

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address

/** Handle for un-registering a `registerOnChange` subscription. */
typealias UnregisterHandle = () -> Unit

/**
 * Resolves the active network's IPv4 default gateway and watches for changes.
 *
 * Two entry points:
 * - [currentGateway] is cheap, polls on demand. Returns null if no IPv4 default
 *   route exists (e.g. IPv6-only network, CGN mobile carrier, offline).
 * - [registerOnChange] registers a [ConnectivityManager.NetworkCallback] on
 *   `onLinkPropertiesChanged` + `onCapabilitiesChanged`, debounced 1s so rapid
 *   AP-roam events collapse into a single callback invocation.
 */
class GatewayResolver(private val connectivityManager: ConnectivityManager) {

    private val tag = "PingCore.Gateway"

    fun currentGateway(): String? {
        val network = connectivityManager.activeNetwork ?: return null
        val linkProps = connectivityManager.getLinkProperties(network) ?: return null
        val entries = linkProps.routes.map { routeInfoToEntry(it) }
        return pickDefaultIPv4Gateway(entries)
    }

    fun registerOnChange(callback: (String?) -> Unit): UnregisterHandle {
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null

        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                scheduleDebounced()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                scheduleDebounced()
            }

            private fun scheduleDebounced() {
                pending?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    try {
                        callback(currentGateway())
                    } catch (e: Exception) {
                        Log.w(tag, "registerOnChange callback threw", e)
                    }
                }
                pending = r
                handler.postDelayed(r, DEBOUNCE_MS)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, netCallback)

        return {
            pending?.let { handler.removeCallbacks(it) }
            try {
                connectivityManager.unregisterNetworkCallback(netCallback)
            } catch (e: IllegalArgumentException) {
                // Callback already unregistered (e.g. double-unregister on rapid teardown).
                Log.w(tag, "unregisterNetworkCallback: callback already absent")
            }
        }
    }

    private fun routeInfoToEntry(r: RouteInfo): RouteEntry {
        val gw = r.gateway
        val isV4 = gw is Inet4Address
        val host = if (isV4) gw?.hostAddress else null
        // Some default routes have a zero-address gateway (0.0.0.0) on local-only
        // networks — treat these as "no gateway" so we don't ping ourselves.
        val normalizedHost = if (host == "0.0.0.0") null else host
        return RouteEntry(
            isDefault = r.isDefaultRoute,
            gatewayHost = if (isV4) normalizedHost else null
        )
    }

    companion object {
        private const val DEBOUNCE_MS = 1_000L
    }
}

/**
 * Minimal route descriptor for the pure [pickDefaultIPv4Gateway] helper.
 * `gatewayHost == null` when the route is IPv6, lacks a nexthop, or is the
 * 0.0.0.0 self-address.
 */
internal data class RouteEntry(
    val isDefault: Boolean,
    val gatewayHost: String?
)

internal fun pickDefaultIPv4Gateway(entries: List<RouteEntry>): String? =
    entries.firstOrNull { it.isDefault && it.gatewayHost != null }?.gatewayHost
