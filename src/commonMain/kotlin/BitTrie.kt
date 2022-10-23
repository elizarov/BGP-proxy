class BitTrie {
    private val root = Node()

    fun add(prefix: IpAddressPrefix) {
        var cur = root
        val prev = ArrayList<Node>()
        for (i in 0 until prefix.length) {
            if (cur.present) return
            prev += cur
            cur = cur[prefix.bitAt(i)]
        }
        cur.prefix = prefix
        cur.markPresent(true)
        var j = prev.lastIndex
        while (j >= 0) {
            val p = prev[j]
            if (p.c0?.present != true || p.c1?.present != true) break
            p.markPresent(true)
            j--
        }
    }

    fun remove(prefix: IpAddressPrefix) {
        var cur = root
        var present = false
        for (i in 0 until prefix.length) {
            val bit = prefix.bitAt(i)
            if (cur.present) present = true
            if (present) {
                cur.present = false
                cur[1 - bit].markPresent(true)
            }
            cur = cur[bit]
        }
        cur.prefix = prefix
        cur.markPresent(false)

    }

    fun toSet(): Set<IpAddressPrefix> {
        val result = mutableSetOf<IpAddressPrefix>()
        val bits = ByteArray(4)
        fun scan(cur: Node, length: Int) {
            if (cur.present) {
                result += cur.prefix ?: IpAddressPrefix(length, bits.copyOf(prefixBytes(length)))
                return
            }
            if (length == 32) return
            cur.c0?.let { scan(it, length + 1) }
            bits.setBitAt(length)
            cur.c1?.let { scan(it, length + 1) }
            bits.clearBitAt(length)
        }
        scan(root, 0)
        return result
    }

    private class Node(
        var c0: Node? = null,
        var c1: Node? = null,
        var prefix: IpAddressPrefix? = null,
        var present: Boolean = false
    ) {
        operator fun get(bit: Int): Node = when(bit) {
            0 -> c0 ?: Node().also { c0 = it }
            1 -> c1 ?: Node().also { c1 = it }
            else -> error("bit=$bit")
        }

        fun markPresent(present: Boolean) {
            this.present = present
            c0 = null
            c1 = null
        }
    }
}

fun Set<IpAddressPrefix>.toBitTrie() = BitTrie().apply { forEach { add(it) } }

fun ByteArray.bitAt(i: Int) = (get(i / 8).toInt() shr (7 - i % 8)) and 1

fun ByteArray.setBitAt(i: Int) {
    val index = i / 8
    val mask = 1 shl (7 - i % 8)
    val old = get(index).toInt()
    val new =  old or mask
    set(index, new.toByte())
}

fun ByteArray.clearBitAt(i: Int) {
    val index = i / 8
    val mask = 1 shl (7 - i % 8)
    val old = get(index).toInt()
    val new = old and mask.inv()
    set(index, new.toByte())
}