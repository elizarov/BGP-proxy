import kotlinx.coroutines.channels.SendChannel
import kotlin.time.TimeSource

class DnsNameResolveCache {
    data class Entry(
        val ips: Set<IpAddress>,
        val expiration: TimeSource.Monotonic.ValueTimeMark
    )

    private class Node(val parent: Node?) {
        val map = HashMap<Label, Node>()
        var entry: Entry? = null
        var allIps: Set<IpAddress>? = null
        var channels: ArrayList<SendChannel<ResolveResult>>? = null

        fun computeAllIps(): Set<IpAddress> {
            allIps?.let { return it }
            val list = map.values.mapTo(ArrayList()) { it.computeAllIps() }
            entry?.let { list += it.ips }
            val result = when {
                list.isEmpty() -> emptySet<IpAddress>()
                list.size == 1 -> list.first()
                else -> {
                    val all = list.flatMapTo(HashSet<IpAddress>()) { it }
                    if (all == list.first()) list.first() else all
                }
            }
            allIps = result
            return result
        }
    }

    private val root = Node(null)

    private val nodeWalkAction = { label: Label, node: Node ->
        node.map.getOrPut(label) { Node(node) }
    }

    private fun getNode(name: DnsName?): Node = reverseNameWalk(name, root, nodeWalkAction)

    // Results:
    // * null -- nothing updated
    // * empty list -- updated, but nothing to notify
    fun put(name: DnsName, entry: Entry): List<Pair<SendChannel<ResolveResult>, ResolveResult>>? {
        val node = getNode(name)
        val updated = node.entry?.ips != entry.ips
        node.entry = entry
        if (!updated) return null
        var cur: Node? = node
        var lastChannel: Node? = null
        while (cur != null) {
            cur.allIps = null
            if (cur.channels?.isNotEmpty() == true) lastChannel = cur
            cur = cur.parent
        }
        if (lastChannel == null) return emptyList()
        val result = ArrayList<Pair<SendChannel<ResolveResult>, ResolveResult>>()
        cur = node
        while (cur != null) {
            val channels = cur.channels
            if (channels != null && channels.isNotEmpty()) {
                val ips = ResolveResult.Ok(cur.computeAllIps())
                for (channel in channels) {
                    result += channel to ips
                }
            }
            if (cur == lastChannel) break
            cur = cur.parent
        }
        return result
    }

    fun addPrefixFlowChannel(name: DnsName?, channel: SendChannel<ResolveResult>): ResolveResult {
        val node = getNode(name)
        val channels = node.channels ?:
            ArrayList<SendChannel<ResolveResult>>().also { node.channels = it }
        channels.add(channel)
        return ResolveResult.Ok(node.computeAllIps())
    }

    fun removePrefixFlowChannel(name: DnsName?, channel: SendChannel<ResolveResult>) {
        val node = getNode(name)
        check(node.channels?.remove(channel) == true) { "Removing missing channel for $name" }
    }
}

private class Label(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Label && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

private fun <T> reverseNameWalk(name: DnsName?, init: T, action: (Label, T) -> T): T {
    if (name == null) return init
    val value = reverseNameWalk(name.next, init, action)
    return action(Label(name.label), value)
}

