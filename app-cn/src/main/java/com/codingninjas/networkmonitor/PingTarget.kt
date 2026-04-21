package com.codingninjas.networkmonitor

import com.pulseboard.core.PingResult

/**
 * One of CN's 4 v1.1 ping targets.
 *
 * `resolveAddress` is called per sample — returns null to signal "no address
 * resolvable right now" (e.g. gateway on CGN mobile data). The sampler emits a
 * sample with `unreachable=true` in that case instead of pinging.
 *
 * `sampler` is the protocol-specific probe — either `PingEngine.runPing(address, ...)`
 * for ICMP targets or `UdpDnsPinger.runQuery()` for the UDP-DNS target.
 *
 * This abstraction lives in :app-cn (not :core) because the target vocabulary
 * — Smartflo/gateway/cloudflare/dns — is CN's product decision. Pulseboard's
 * public build will define its own target list (probably user-configurable
 * rather than a fixed enum).
 */
data class PingTarget(
    val id: String,
    val resolveAddress: () -> String?,
    val sampler: (address: String) -> PingResult
)
