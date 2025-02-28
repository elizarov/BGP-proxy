import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.collections.ArrayList
import kotlin.time.Duration
import kotlin.time.TimeSource

class DnsNameResolveCache {
    private data class Entry(
        val answer: List<DnsAnswer>,
        val ips: Set<IpAddress>,
        val time: TimeSource.Monotonic.ValueTimeMark,
        val expiration: TimeSource.Monotonic.ValueTimeMark
    )

    private class Node(val parent: Node?) {
        val map = HashMap<Label, Node>()
        var entry: Entry? = null
        var allIps: Set<IpAddress>? = null
        var channels: ArrayList<SendChannel<ResolveResult>>? = null

        val isEmpty: Boolean get() = entry == null && map.isEmpty() && (channels?.isEmpty() ?: true)

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

        // returns true if updated anything
        fun cleanupImpl(
            now: TimeSource.Monotonic.ValueTimeMark,
            cacheExtraTll: Duration,
            result: ArrayList<Pair<SendChannel<ResolveResult>, ResolveResult>>
        ): Boolean {
            var updated = false
            val it = map.values.iterator()
            while (it.hasNext()) {
                val node = it.next()
                if (node.cleanupImpl(now, cacheExtraTll, result)) updated = true
                if (node.isEmpty) it.remove()
            }
            entry?.expiration?.let { expiration ->
                if (now > expiration + cacheExtraTll) {
                    entry = null
                    updated = true
                }
            }
            if (updated) allIps = null
            if (updated && channels?.isNotEmpty() == true) {
                val ips = computeAllIps()
                for (channel in channels) result += channel to ResolveResult.Ok(ips)
            }
            return updated
        }
    }

    private val mutex = Mutex()
    private val root = Node(null)

    private val nodeCreateWalkAction = { label: Label, node: Node ->
        node.map.getOrPut(label) { Node(node) }
    }

    private val nodeOrNullWalkAction = { label: Label, node: Node? ->
        node?.map?.get(label)
    }

    private fun getNode(name: DnsName?): Node = reverseNameWalk(name, root, nodeCreateWalkAction)
    private fun getNodeOrNull(name: DnsName?): Node? = reverseNameWalk(name, root, nodeOrNullWalkAction)

    /**
     * Note: it modifies [ResolveResult.Ok.hadUpdatedWildcards] in the [result].
     */
    suspend fun putResolveAnswer(
        name: DnsName,
        answer: List<DnsAnswer>,
        result: ResolveResult.Ok
    )  {
        val now = TimeSource.Monotonic.markNow()
        val expiration = now + result.ttl
        val entry = Entry(answer, result.addresses.toSet(), now, expiration)
        var sendChannels: ArrayList<Pair<SendChannel<ResolveResult>, ResolveResult>>? = null
        mutex.withLock {
            val node = getNode(name)
            val updated = node.entry?.ips != entry.ips
            node.entry = entry
            if (!updated) return@withLock
            var cur: Node? = node
            var lastChannel: Node? = null
            while (cur != null) {
                cur.allIps = null
                if (cur.channels?.isNotEmpty() == true) lastChannel = cur
                cur = cur.parent
            }
            if (lastChannel == null) return@withLock
            sendChannels = ArrayList<Pair<SendChannel<ResolveResult>, ResolveResult>>()
            cur = node
            while (cur != null) {
                val channels = cur.channels
                if (channels != null && channels.isNotEmpty()) {
                    val ips = ResolveResult.Ok(cur.computeAllIps())
                    for (channel in channels) {
                        sendChannels += channel to ips
                    }
                }
                if (cur == lastChannel) break
                cur = cur.parent
            }
        }
        if (sendChannels == null) return
        for ((channel, resolve) in sendChannels) {
            channel.send(resolve)
        }
        result.hadUpdatedWildcards = true
    }

    suspend fun getResolveAnswer(name: DnsName): List<DnsAnswer>? = mutex.withLock {
        val entry = getNodeOrNull(name)?.entry ?: return@withLock null
        val now = TimeSource.Monotonic.markNow()
        if (now > entry.expiration) return@withLock null
        val adjustTtl = (now - entry.time).inWholeSeconds.coerceAtLeast(0).toUInt()
        entry.answer.map { it.copy(ttl = if (it.ttl > adjustTtl) it.ttl - adjustTtl else 1u) }
    }

    suspend fun addPrefixFlowChannel(name: DnsName?, channel: SendChannel<ResolveResult>): ResolveResult = mutex.withLock {
        val node = getNode(name)
        val channels = node.channels ?:
            ArrayList<SendChannel<ResolveResult>>().also { node.channels = it }
        channels.add(channel)
        ResolveResult.Ok(node.computeAllIps())
    }

    suspend fun removePrefixFlowChannel(name: DnsName?, channel: SendChannel<ResolveResult>): Unit = mutex.withLock {
        val node = getNode(name)
        check(node.channels?.remove(channel) == true) { "Removing missing channel for $name" }
    }

    suspend fun cleanup(cacheExtraTtl: Duration) {
        val now = TimeSource.Monotonic.markNow()
        val result = ArrayList<Pair<SendChannel<ResolveResult>, ResolveResult>>()
        mutex.withLock {
            root.cleanupImpl(now, cacheExtraTtl, result)
        }
        for ((channel, resolve) in result) {
            channel.send(resolve)
        }
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

