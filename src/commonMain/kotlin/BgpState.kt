@file:OptIn(ExperimentalUnsignedTypes::class)

import io.ktor.utils.io.core.*

data class BgpUpdate(
    val withdrawn: Set<IpAddressPrefix> = emptySet(),
    val reachable: Set<IpAddressPrefix> = emptySet(),
    val attributes: BgpAttributes = emptyList()
) {
    override fun toString(): String = buildList {
        if (withdrawn.isNotEmpty()) add("withdrawn ${withdrawn.size}")
        if (reachable.isNotEmpty()) add("reachable ${reachable.size}")
        if (attributes.isNotEmpty()) add("attrs ${attributes.joinToString(" ")}")
    }.joinToString(", ")
}

data class BgpState(
    val prefixes: Map<IpAddressPrefix, BgpCommunities> = emptyMap()
) {
    fun apply(update: BgpUpdate): BgpState {
        val newPrefixes = prefixes.toMutableMap()
        newPrefixes -= update.withdrawn
        if (update.reachable.isNotEmpty()) {
            val communities = update.attributes.communities
            for (prefix in update.reachable) newPrefixes[prefix] = communities
        }
        return BgpState(newPrefixes)
    }

    fun diffFrom(lastState: BgpState): BgpDiff {
        val withdrawn = ArrayList<IpAddressPrefix>()
        val reachable = HashMap<BgpCommunities, ArrayList<IpAddressPrefix>>()
        for ((prefix, _) in lastState.prefixes) {
            if (prefixes[prefix] == null) withdrawn += prefix
        }
        for ((prefix, communities) in prefixes) {
            if (lastState.prefixes[prefix] != communities) reachable.getOrPut(communities) { ArrayList() } += prefix
        }
        return BgpDiff(
            withdrawn = withdrawn,
            reachable = reachable,
        )
    }

    private val communities: List<Pair<BgpCommunity, Int>> by lazy {
        prefixes.values.asSequence().flatten().groupingBy { it }.eachCount().toList().sortedBy { it.first }
    }

    override fun toString(): String = buildString {
        append(prefixes.size)
        append(" IPs")
        if (communities.isNotEmpty()) {
            append(" from ")
            append(communities.joinToString(" ") { "${it.first}(${it.second})" })
        }
    }
}

data class BgpDiff(
    val withdrawn: List<IpAddressPrefix>,
    val reachable: Map<BgpCommunities, List<IpAddressPrefix>>
) {
    override fun toString(): String = buildList {
        if (withdrawn.isNotEmpty()) add("withdrawn ${withdrawn.size}")
        if (reachable.isNotEmpty()) add("reachable ${reachable.map { it.value.size }.sum()}")
    }.joinToString(", ")
}

fun ByteReadPacket.readPrefixes(totalLength: Int): Set<IpAddressPrefix> = buildSet {
    var remaining = totalLength
    while (remaining > 0) {
        val length = readUByte().toInt()
        check(length in 0..32) { "BGP: Prefix length must be in range 0..32 but got: $length" }
        val n = prefixBytes(length)
        val prefix = readBytes(n)
        add(IpAddressPrefix(length, prefix))
        remaining -= 1 + n
    }
}

fun composePrefixes(prefixes: ArrayDeque<IpAddressPrefix>, maxLength: Int) = buildPacket {
    var remBytes = maxLength
    while (prefixes.isNotEmpty()) {
        if (remBytes <= 0) break
        val prefix = prefixes.removeFirst()
        val length = prefix.length
        writeByte(length.toByte())
        for (i in 0 until prefix.nBytes) writeByte(prefix[i])
        remBytes -= 1 + prefixBytes(length)
    }
}
