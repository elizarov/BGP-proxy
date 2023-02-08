class BitTrie {
    private val root = Node()

    fun add(prefix: IpAddressPrefix, communities: BgpCommunities) {
        var cur = root
        val prev = ArrayList<Node>()
        // Push down the trie, expanding as needed
        for (i in 0 until prefix.length) {
            if (cur.communities == communities) return
            if (cur.communities != null) cur.expand()
            prev += cur
            cur = cur.getOrCreate(prefix.bitAt(i))
        }
        // add to the current node
        val topCommunities = cur.communities?.let { it + communities } ?: communities
        cur.prefix = prefix
        // Add to all the leaves below this node and merge on the way back all equal nodes
        // addRec returns "true" if all communities below cur node are equal to topCommunities
        fun addRec(cur: Node): Boolean {
            if (cur.communities != null || (cur.c0 == null && cur.c1 == null)) {
                // was leaf or new leaf
                val newCommunities = cur.communities?.let { it + communities } ?: communities
                cur.communities = newCommunities
                return newCommunities == topCommunities
            }
            // non-leaf
            val same0 = cur.c0?.let { addRec(it) } ?: true
            val same1 = cur.c1?.let { addRec(it) } ?: true
            cur.tryCombine()
            return same0 && same1
        }
        if (addRec(cur)) { // all the same downwards
            cur.makeLeaf(topCommunities)
        }
        // Combine up the trie all in the same community
        for (i in prev.lastIndex downTo 0) {
            if (!prev[i].tryCombine()) break
        }
    }

    fun remove(prefix: IpAddressPrefix) {
        var cur = root
        val prev = ArrayList<Node>()
        // Expand down the trie
        for (i in 0 until prefix.length) {
            if (cur.communities != null) cur.expand()
            prev += cur
            cur = cur.get(prefix.bitAt(i)) ?: return // was not present at all -- bail out
        }
        // Drop extra nodes up the tree as needed
        for (i in prev.lastIndex downTo 0) {
            val p = prev[i]
            val bit = prefix.bitAt(i)
            p.set(bit, null)
            val other = p.get(1 - bit)
            if (other != null) break // the other branch is there
        }
    }

    fun toMap(): Map<IpAddressPrefix, BgpCommunities> {
        val result = LinkedHashMap<IpAddressPrefix, BgpCommunities>()
        fun scan(cur: Node, length: Int, bits: Int) {
            cur.communities?.let { communities ->
                check(cur.c0 == null && cur.c1 == null)
                result[cur.prefix ?: IpAddressPrefix(length, bits)] = communities
                return
            }
            if (length == 32) return
            cur.c0?.let { scan(it, length + 1, bits) }
            cur.c1?.let { scan(it, length + 1, bits or (1 shl (31 - length))) }
        }
        scan(root, 0, 0)
        return result
    }

    private class Node(
        var c0: Node? = null,
        var c1: Node? = null,
        var prefix: IpAddressPrefix? = null,
        var communities: BgpCommunities? = null // != null on all leaf nodes, except the root of empty trie
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

        fun makeLeaf(communities: BgpCommunities) {
            this.communities = communities
            c0 = null
            c1 = null
        }

        fun tryCombine(): Boolean {
            val newCommunities = c0?.communities
            if (newCommunities != null && c1?.communities == newCommunities) {
                makeLeaf(newCommunities)
                return true
            }
            return false
        }

        fun expand() {
            check(c0 == null)
            check(c1 == null)
            c0 = Node(communities = communities)
            c1 = Node(communities = communities)
            communities = null
        }
    }
}

fun Map<IpAddressPrefix, BgpCommunities>.toBitTrie() =
    BitTrie().apply { forEach { add(it.key, it.value) } }
