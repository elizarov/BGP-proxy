data class BgpOverrideParseResult(
    val update: BgpUpdate,
    val errors: List<String>
)

fun parseOverrideFile(overrideFile: String): BgpOverrideParseResult {
    val lines = readFileBytesCatching(overrideFile)
        .getOrElse { ex -> return BgpOverrideParseResult(BgpUpdate(), listOf(ex.toString())) }
        .decodeToString().split("\n")
    val withdrawn = mutableSetOf<IpAddressPrefix>()
    val reachable = mutableSetOf<IpAddressPrefix>()
    val errors = ArrayList<String>()
    for ((index, line0) in lines.withIndex()) {
        val line = line0.substringBefore('#').trim()
        if (line.isEmpty()) continue
        val op = line[0]
        val address = line.substring(1).trim()
        val ip = parsePrefixOrNull(address)
        if (ip == null) {
            errors += "Line ${index + 1}: Invalid ip address prefix '$address'"
            continue
        }
        when (op) {
            '+' -> reachable += ip
            '-' -> withdrawn += ip
            else -> errors += "Line ${index + 1}: Invalid operation '$op'"
        }
    }
    return BgpOverrideParseResult(BgpUpdate(withdrawn, reachable), errors)
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

fun BgpState.applyOverride(override: BgpUpdate): BgpState {
    val bt = prefixes.toBitTrie()
    val (withdrawn, reachable) = override
    reachable.forEach { bt.add(it) }
    withdrawn.forEach { bt.remove(it) }
    return BgpState(bt.toSet(), attributes)
}