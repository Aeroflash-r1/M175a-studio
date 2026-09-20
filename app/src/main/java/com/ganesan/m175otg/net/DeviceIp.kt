package com.ganesan.m175otg.net

/**
 * Local IPv4 lookup for bridge hosting.
 *
 * The old phone shows one of these in Setup so the second phone can type
 * it as the Wi-Fi bridge address. Prefers LAN/hotspot ranges
 * (192.168/10./172.16-31) over anything else.
 */
object DeviceIp {

    /** All non-loopback IPv4 addresses on up interfaces. */
    fun ipv4Addrs(): List<String> {
        val out = ArrayList<String>()
        try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifs) {
                runCatching {
                    if (!ni.isUp || ni.isLoopback) return@runCatching
                    for (a in ni.inetAddresses) {
                        if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                            out.add(a.hostAddress ?: continue)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return out.distinct()
    }

    /** Best single address to display, or null when offline. */
    fun best(): String? {
        val all = ipv4Addrs()
        if (all.isEmpty()) return null
        return all.firstOrNull { it.startsWith("192.168.") }
            ?: all.firstOrNull { it.startsWith("10.") }
            ?: all.firstOrNull {
                it.startsWith("172.") &&
                        (it.split(".").getOrNull(1)?.toIntOrNull() ?: 0) in 16..31
            }
            ?: all.first()
    }
}
