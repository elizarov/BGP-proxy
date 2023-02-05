import kotlin.test.Test
import kotlin.test.assertEquals

class BitTrieTest {
    @Test
    fun testBasics() {
        val bt = BitTrie()
        val ca = setOf(BgpCommunity(1u, 2u))
        assertEquals(emptyMap(), bt.toMap())
        val p1 = IpAddressPrefix(10, byteArrayOf("10101110".toInt(2).toByte(), "11000000".toInt(2).toByte()))
        bt.set(p1, ca)
        assertEquals(setOf(p1).associateWith { ca }, bt.toMap())
        val p2 = IpAddressPrefix(4, byteArrayOf("10100000".toInt(2).toByte()))
        bt.set(p2, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
        bt.set(p1, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
        val p3 = IpAddressPrefix(6, byteArrayOf("10101100".toInt(2).toByte()))
        bt.set(p3, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
        bt.remove(p3)
        val p2a = IpAddressPrefix(5, byteArrayOf("10100000".toInt(2).toByte()))
        val p2b = IpAddressPrefix(6, byteArrayOf("10101000".toInt(2).toByte()))
        assertEquals(setOf(p2a, p2b).associateWith { ca }, bt.toMap())
        bt.set(p1, ca)
        assertEquals(setOf(p1, p2a, p2b).associateWith { ca }, bt.toMap())
        bt.set(p3, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
        // Another community
        val cb = setOf(BgpCommunity(3u, 4u))
        bt.set(p3, cb)
        assertEquals(mapOf(p3 to cb, p2a to ca, p2b to ca), bt.toMap())
        // Fix it up to first community again
        bt.set(p3, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
    }
}