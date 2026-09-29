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

/** How a gateway IP was obtained. Emitted as the `gateway_resolver_source` Sheet column. */
enum class GatewaySource { LIVE, CACHED, NULL }

/** (ip, source) pair — `ip==null` iff source == NULL. */
data class GatewayLookup(val ip: String?, val source: GatewaySource)

/**
 * Resolves the active network's IPv4 default gateway and watches for changes.
 *
 * v1.2: also caches the last non-null live value so transient null moments
 * (network state flap, MIUI screen-off) don't produce empty `gateway_ip` rows.
 * The cache is cleared only when registerOnChange observes a fresh null
 * via the change callback (genuine disconnect) — a bare poll returning null
 * falls back to the cache.
 *
 * Two entry points:
 * - [currentGatewayWithSource] returns (ip, "live"/"cached"/"null") — new in v1.2.
 * - [currentGateway] preserves the v1.1 signature (ip-only) for back-compat.
 * - [registerOnChange] registers a [ConnectivityManager.NetworkCallback] on
 *   `onLinkPropertiesChanged` + `onCapabilitiesChanged`, debounced 1s so rapid
 *   AP-roam events collapse into a single callback invocation.
 */
class GatewayResolver(private val connectivityManager: ConnectivityManager) {

    private val tag = "PingCore.Gateway"

    @Volatile private var lastGoodGateway: String? = null

    fun currentGatewayWithSource(): GatewayLookup {
        val live = queryLive()
        return if (live != null) {
            lastGoodGateway = live
            GatewayLookup(live, GatewaySource.LIVE)
        } else {
            val cached = lastGoodGateway
            if (cached != null) GatewayLookup(cached, GatewaySource.CACHED)
            else GatewayLookup(null, GatewaySource.NULL)
        }
    }

    /** Back-compat shim — returns only the IP, falling back to cache. */
    fun currentGateway(): String? = currentGatewayWithSource().ip

    /** Forces a live query, ignoring the cache. Used internally and by tests. */
    private fun queryLive(): String? {
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

            override fun onLost(network: Network) {
                // Genuine disconnect: clear the cache so a real null reaches the
                // next flush. Transient null-polls will now return (null, NULL)
                // rather than a stale cached value from minutes ago.
                lastGoodGateway = null
                scheduleDebounced()
            }

            private fun scheduleDebounced() {
                pending?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    try {
                        val live = queryLive()
                        if (live != null) lastGoodGateway = live
                        callback(live)
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

/**
 * Pure helper for the (ip, source) decision given a live-query outcome and a
 * cached value. Extracted so tests can exercise the cache logic without a
 * full ConnectivityManager mock.
 */
internal fun resolveGatewayWithCache(live: String?, cached: String?): GatewayLookup {
    if (live != null) return GatewayLookup(live, GatewaySource.LIVE)
    if (cached != null) return GatewayLookup(cached, GatewaySource.CACHED)
    return GatewayLookup(null, GatewaySource.NULL)
}
