enum class BgpOverrideOp { PLUS, MINUS }

data class BgpOverride<T : AddressRange>(val op: BgpOverrideOp, val host: T)

data class BgpOverrideParseResult(
    val overrides: List<BgpOverride<AddressRange>> = emptyList(),
    val errors: List<String> = emptyList()
)

fun parseOverrideFile(overrideFile: String): BgpOverrideParseResult {
    val lines = readFileBytesCatching(overrideFile)
        .getOrElse { ex -> return BgpOverrideParseResult(errors = listOf(ex.toString())) }
        .decodeToString().split("\n")
    val overrides = ArrayList<BgpOverride<AddressRange>>()
    val errors = ArrayList<String>()
    for ((index, line0) in lines.withIndex()) {
        val line = line0.substringBefore('#').trim()
        if (line.isEmpty()) continue
        val op = when (line[0]) {
            '+' -> BgpOverrideOp.PLUS
            '-' -> BgpOverrideOp.MINUS
            else -> {
                errors += "Line ${index + 1}: Invalid operation '$line'"
                continue
            }
        }
        val address = line.substring(1).trim()
        val addressRange: AddressRange? = if (address.isEmpty() || address[0] in '0'..'9') {
            parsePrefixOrNull(address)
        } else {
            HostName(address)
        }
        if (addressRange == null) {
            errors += "Line ${index + 1}: Invalid ip address prefix '$address'"
            continue
        }
        overrides += BgpOverride(op, addressRange)
    }
    return BgpOverrideParseResult(overrides, errors)
}

fun parsePrefixOrNull(s: String): IpAddressPrefix? {
    val length = s.substringAfter('/', "32").toIntOrNull() ?: return null
    if (length !in 0..32) return null
    val ip = s.substringBefore('/').split(".")
        .map { (it.toUByteOrNull() ?: return null).toByte() }.toByteArray()
    if (ip.size != 4) return null
    for (i in length until 24) if (ip.bitAt(i) != 0) return null
    return IpAddressPrefix(length, ip.copyOf(prefixBytes(length)))
}

fun ByteArray.bitAt(i: Int) = (get(i / 8).toInt() shr (7 - i % 8)) and 1

fun BgpState.applyOverrides(overrides: List<BgpOverride<IpAddressPrefix>>): BgpState {
    val bt = prefixes.toBitTrie()
    for ((op, prefix) in overrides) when(op) {
        BgpOverrideOp.PLUS -> bt.add(prefix)
        BgpOverrideOp.MINUS -> bt.remove(prefix)
    }
    return BgpState(bt.toSet(), attributes)
}