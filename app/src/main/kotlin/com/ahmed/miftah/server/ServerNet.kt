package com.ahmed.miftah.server

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Network-reach helpers for the local server.
 *
 * The pure functions (bind host, auth check, address classification/selection, URL
 * formatting) are deliberately free of Android APIs so they can be unit-tested on
 * the JVM; only [detectAdvertised] touches the live network interfaces.
 */
object ServerNet {
    const val LOCAL_HOST = "127.0.0.1"
    const val ALL_HOSTS = "0.0.0.0"

    /** Loopback-only unless the user explicitly opted into network reach. */
    fun bindHost(networkMode: Boolean): String = if (networkMode) ALL_HOSTS else LOCAL_HOST

    /**
     * The unified key is required for EVERY request in both bind modes.
     * An empty key never authorizes anything (fail closed).
     */
    fun isAuthorized(authorizationHeader: String?, unifiedKey: String): Boolean {
        if (unifiedKey.isEmpty()) return false
        return authorizationHeader == "Bearer $unifiedKey"
    }

    fun baseUrl(host: String, port: Int): String = "http://$host:$port/v1"

    fun localBaseUrl(port: Int): String = baseUrl(LOCAL_HOST, port)

    enum class AddressKind { TAILSCALE, LAN }

    /**
     * Classifies an IPv4 address for advertising to other devices:
     * Tailscale's CGNAT range 100.64.0.0/10, or a private LAN range
     * (10/8, 172.16/12, 192.168/16). Anything else (loopback, link-local,
     * public, malformed, IPv6) returns null and is never advertised.
     */
    fun classify(ip: String): AddressKind? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val o = parts.map { it.toIntOrNull() ?: return null }
        if (o.any { it !in 0..255 }) return null
        return when {
            o[0] == 100 && o[1] in 64..127 -> AddressKind.TAILSCALE
            o[0] == 10 -> AddressKind.LAN
            o[0] == 172 && o[1] in 16..31 -> AddressKind.LAN
            o[0] == 192 && o[1] == 168 -> AddressKind.LAN
            else -> null
        }
    }

    data class AdvertisedAddress(val ip: String, val kind: AddressKind)

    /** Filters to reachable private addresses, dedupes, Tailscale first then LAN. */
    fun selectAdvertised(ips: List<String>): List<AdvertisedAddress> {
        val seen = LinkedHashMap<String, AddressKind>()
        for (ip in ips) {
            val kind = classify(ip) ?: continue
            if (ip !in seen) seen[ip] = kind
        }
        return seen.entries
            .map { AdvertisedAddress(it.key, it.value) }
            .sortedBy { if (it.kind == AddressKind.TAILSCALE) 0 else 1 }
    }

    /** Live IPv4 addresses of up, non-loopback interfaces (wlan, tailscale tun, …). */
    fun detectAdvertised(): List<AdvertisedAddress> {
        val ips = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces.toList()) {
                val up = try { iface.isUp && !iface.isLoopback } catch (e: Exception) { false }
                if (!up) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return selectAdvertised(ips)
    }
}
