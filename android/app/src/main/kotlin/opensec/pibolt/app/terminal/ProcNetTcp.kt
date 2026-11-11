package opensec.pibolt.app.terminal

/**
 * Parses `/proc/net/tcp` and `/proc/net/tcp6`: the TCP sockets in LISTEN state, with the uid that owns each one.
 *
 * A line (after the header) looks like
 * `   0: 0100007F:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234        0 81234 1 ...`:
 * the local address is hex, each 32-bit word in host (little-endian) byte order, then the port in hex; `0A` is LISTEN;
 * the eighth field is the uid.
 */
object ProcNetTcp {
    /** A listening socket a browser on this phone can reach as http://localhost:[port]. */
    data class Listener(val port: Int, val address: String, val uid: Int)

    private const val LISTEN = "0A"

    /** Every listening socket in [text] bound to loopback or to every address (others are not on localhost). */
    fun parse(text: String): List<Listener> = text.lineSequence().mapNotNull(::parseLine).toList()

    /** The ports [uid] listens on, sorted, without duplicates (one server often listens on both tcp and tcp6). */
    fun portsOf(uid: Int, vararg tables: String): List<Int> =
        tables.flatMap(::parse).filter { it.uid == uid }.map { it.port }.distinct().sorted()

    private fun parseLine(line: String): Listener? {
        val fields = line.trim().split(WHITESPACE)
        if (fields.size < 8 || !fields[0].endsWith(':')) return null
        if (fields[3] != LISTEN) return null
        val local = fields[1]
        val colon = local.lastIndexOf(':')
        if (colon <= 0) return null
        val port = local.substring(colon + 1).toIntOrNull(16)?.takeIf { it in 1..65535 } ?: return null
        val address = decodeAddress(local.substring(0, colon)) ?: return null
        if (!reachableAsLocalhost(address)) return null
        val uid = fields[7].toIntOrNull() ?: return null
        return Listener(port, address, uid)
    }

    /** Dotted IPv4 for 8 hex digits, else a full (uncompressed) IPv6 form for 32 hex digits. */
    internal fun decodeAddress(hex: String): String? {
        if (hex.length != 8 && hex.length != 32) return null
        val bytes = ByteArray(hex.length / 2)
        // Each 32-bit word is printed as a host-order integer: reverse the 4 bytes of every word.
        for (word in 0 until hex.length / 8) {
            val value = hex.substring(word * 8, word * 8 + 8).toLongOrNull(16) ?: return null
            for (b in 0 until 4) bytes[word * 4 + b] = ((value shr (8 * b)) and 0xFF).toByte()
        }
        if (bytes.size == 4) return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        return (0 until 8).joinToString(":") { i ->
            val hi = bytes[i * 2].toInt() and 0xFF
            val lo = bytes[i * 2 + 1].toInt() and 0xFF
            ((hi shl 8) or lo).toString(16)
        }
    }

    private fun reachableAsLocalhost(address: String): Boolean = when {
        address == "0.0.0.0" || address.startsWith("127.") -> true
        address == "0:0:0:0:0:0:0:0" || address == "0:0:0:0:0:0:0:1" -> true
        // IPv4-mapped: ::ffff:127.x.x.x and ::ffff:0.0.0.0.
        address.startsWith("0:0:0:0:0:ffff:") -> {
            val tail = address.removePrefix("0:0:0:0:0:ffff:").split(':')
            val first = tail.firstOrNull()?.toIntOrNull(16) ?: -1
            (first shr 8) == 127 || tail.all { it == "0" }
        }
        else -> false
    }

    private val WHITESPACE = Regex("\\s+")
}
