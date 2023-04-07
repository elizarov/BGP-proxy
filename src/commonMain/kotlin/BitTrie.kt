class BitTrie {
    private val root = Node()

    // overrides set of communities for a given IP address prefix
    fun set(prefix: IpAddressPrefix, communities: BgpCommunities) {
        var cur = root
        for (i in 0 until prefix.length) cur = cur.getOrCreate(prefix.bitAt(i))
        cur.communities = communities
        cur.prefix = prefix
    }

    fun add(prefix: IpAddressPrefix, addCommunity: BgpCommunity) = add(prefix, setOf(addCommunity))

    // adds communities to all IP addresses with a given prefix
    fun add(prefix: IpAddressPrefix, addCommunities: BgpCommunities) {
        var cur = root
        val prev = ArrayList<Node>()
        var lastCommunities: BgpCommunities? = null
        for (i in 0 until prefix.length) {
            prev += cur
            if (cur.communities != null) lastCommunities = cur.communities
            cur = cur.getOrCreate(prefix.bitAt(i))
        }
        // Add at this and below this node and merge on the way back all equal nodes
        fun addRec(cur: Node, lastCommunities: BgpCommunities?) {
            val curCommunities = (cur.communities ?: lastCommunities)?.let { it + addCommunities } ?: addCommunities
            cur.communities = if (curCommunities == lastCommunities) null else curCommunities
            cur.communities?.let { communities ->
                cur.communities = communities + addCommunities
            }
            cur.c0?.let { addRec(it, curCommunities) }
            cur.c1?.let { addRec(it, curCommunities) }
            cur.tryCombine()
        }
        addRec(cur, lastCommunities)
        cur.prefix = prefix
        // Combine up the trie all in the same community
        for (i in prev.lastIndex downTo 0) {
            if (!prev[i].tryCombine()) break
        }
    }

    // remove the given prefix completely, so that it is not associated with any communities
    fun remove(prefix: IpAddressPrefix) {
        var cur = root
        var cc: BgpCommunities? = null
        // Expand down the trie
        for (i in 0 until prefix.length) {
            if (cur.communities != null) {
                cc = cur.communities
                cur.communities = null
            }
            val bit = prefix.bitAt(i)
            if (cc != null) {
                val c1 = cur.getOrCreate(1 - bit)
                if (c1.communities == null) c1.communities = cc
            }
            cur = cur.get(bit) ?: return // is not present further down -- bail out
        }
        cur.communities = null
        cur.c0 = null
        cur.c1 = null
    }

    fun toMap(): Map<IpAddressPrefix, BgpCommunities> {
        val result = LinkedHashMap<IpAddressPrefix, BgpCommunities>()
        fun scan(cur: Node, length: Int, bits: Int) {
            cur.communities?.let { communities ->
                result[cur.prefix ?: IpAddressPrefix(length, bits)] = communities
            }
            if (length == 32) return
            cur.c0?.let { scan(it, length + 1, bits) }
            cur.c1?.let { scan(it, length + 1, bits or (1 shl (31 - length))) }
        }
        scan(root, 0, 0)
        return result
    }

    /**
     * Trie invariant: the set of communities that applies to a given IP address is the last
     * non-null value of [communities] that was discovered while traversing the trie.
     */
    private class Node(
        var c0: Node? = null,
        var c1: Node? = null,
        var prefix: IpAddressPrefix? = null,
        var communities: BgpCommunities? = null
    ) {
        fun get(bit: Int): Node? = when(bit) {
            0 -> c0
            1 -> c1
            else -> error("bit=$bit")
        }

        fun set(bit: Int, node: Node?) = when(bit) {
            0 -> c0 = node
            1 -> c1 = node
            else -> error("bit=$bit")
        }

        fun getOrCreate(bit: Int): Node = get(bit) ?: Node().also { set(bit, it) }

        // combine when both children have the same set of communities
        fun tryCombine(): Boolean {
            val c0 = c0 ?: return false
            val c1 = c1 ?: return false
            val cc = c0.communities ?: return false
            if (c1.communities != cc) return false
            communities = cc
            c0.communities = null
            c1.communities = null
            return true
        }
    }
}
