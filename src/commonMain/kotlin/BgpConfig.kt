import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ConfigSource

data class BgpRemoteSource(val host: String) : ConfigSource
data class HostName(val host: String) : ConfigSource

class IpAddressPrefix(
    val length: Int = 32,
    val bits: Int
) : ConfigSource {
    init {
        val mask = (1 shl (32 - length)) - 1
        check(bits and mask == 0)
    }
    val nBytes: Int get() = prefixBytes(length)
    operator fun get(i: Int): Byte = (bits shr ((3 - i) * 8)).toByte()
    fun bitAt(i: Int) = (bits shr (31 - i)) and 1
    override fun toString(): String {
        val sb = StringBuilder()
        for (i in 0 until nBytes) {
            if (i != 0) sb.append('.')
            sb.append(this[i].toUByte())
        }
        if (length < 32) sb.append("/$length")
        return sb.toString()
    }
    override fun equals(other: Any?): Boolean =
        other is IpAddressPrefix && other.length == length && other.bits == bits
    override fun hashCode(): Int = length * 31 + bits
}

fun IpAddressPrefix(length: Int = 32, prefix: ByteArray): IpAddressPrefix {
    val n = prefix.size
    check(n == prefixBytes(length))
    var bits = 0
    for (i in 0 until n) {
        bits = bits or ((prefix[i].toInt() and 0xff) shl ((3 - i) * 8))
    }
    return IpAddressPrefix(length, bits)
}

fun prefixBytes(length: Int) = (length + 7) / 8

enum class BgpConfigOp { PLUS, MINUS }

data class BgpConfigItem<T>(val op: BgpConfigOp, val source: T)

data class BgpConfigParseResult(
    val items: List<BgpConfigItem<ConfigSource>> = emptyList(),
    val errors: List<String> = emptyList()
)

fun parseConfigFile(configFile: String): BgpConfigParseResult {
    val lines = readFileBytesCatching(configFile)
        .getOrElse { ex -> return BgpConfigParseResult(errors = listOf(ex.toString())) }
        .decodeToString().split("\n")
    val items = ArrayList<BgpConfigItem<ConfigSource>>()
    val errors = ArrayList<String>()
    for ((index, line0) in lines.withIndex()) {
        val line = line0.substringBefore('#').trim()
        if (line.isEmpty()) continue
        val op = when (line[0]) {
            '+' -> BgpConfigOp.PLUS
            '-' -> BgpConfigOp.MINUS
            else -> {
                errors += "Line ${index + 1}: Invalid operation '$line'"
                continue
            }
        }
        val sourceSpecFull = line.substring(1).trim()
        val sourceSpec = sourceSpecFull.substringAfter(':').trim()
        val sourceType = sourceSpecFull.substringBefore(':',
            if (sourceSpec.isEmpty() || sourceSpec[0] in '0'..'9') "ip" else "dns"
        ).trim()
        val source: ConfigSource? = when (sourceType.lowercase()) {
            "bgp" -> BgpRemoteSource(sourceSpec)
            "ip" -> parsePrefixOrNull(sourceSpec)
            "dns" -> HostName(sourceSpec)
            else -> null
        }
        if (source == null) {
            errors += "Line ${index + 1}: Invalid source '$sourceSpec'"
            continue
        }
        items += BgpConfigItem(op, source)
    }
    return BgpConfigParseResult(items, errors)
}

fun parsePrefixOrNull(s: String): IpAddressPrefix? {
    val length = s.substringAfter('/', "32").toIntOrNull() ?: return null
    if (length !in 0..32) return null
    val ip = s.substringBefore('/').split(".")
        .map { (it.toUByteOrNull() ?: return null).toByte() }.toByteArray()
    val needBytes = prefixBytes(length)
    if (ip.size > 4 || ip.size < needBytes) return null
    for (i in length until ip.size * 8) if (ip.bitAt(i) != 0) return null
    return IpAddressPrefix(length, ip.copyOf(needBytes))
}

fun CoroutineScope.launchConfigLoader(configFile: String): StateFlow<List<BgpConfigItem<ConfigSource>>> {
    val bgpConfig = MutableStateFlow(emptyList<BgpConfigItem<ConfigSource>>())
    // launch config file tracker
    val log = Log("config")
    launch {
        log("Started tracking config file $configFile")
        var prevErrors = emptyList<String>()
        retryIndefinitely(log, fileRescanDuration) {
            val (items, errors) = parseConfigFile(configFile)
            if (errors != prevErrors) {
                errors.forEach { log("ERROR: $it") }
                prevErrors = errors
            }
            bgpConfig.emit(items)
        }
    }
    // log config changes
    launch {
        bgpConfig.collect { items ->
            log("configured ${items.size} sources " +
                    "(+${items.count { it.op == BgpConfigOp.PLUS}} " +
                    "-${items.count { it.op == BgpConfigOp.MINUS}})")
        }
    }
    return bgpConfig
}