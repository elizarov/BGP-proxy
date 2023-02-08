import kotlin.test.Test
import kotlin.test.assertEquals

class BitTrieTest {
    @Test
    fun testBasics() {
        val bt = BitTrie()
        val ca = setOf(BgpCommunity(1u, 2u))
        assertEquals(emptyMap(), bt.toMap())
        val p1 = IpAddressPrefix(10, byteArrayOf("10101110".toInt(2).toByte(), "11000000".toInt(2).toByte()))
        bt.add(p1, ca)
        assertEquals(setOf(p1).associateWith { ca }, bt.toMap())
        val p2 = IpAddressPrefix(4, byteArrayOf("10100000".toInt(2).toByte()))
        bt.add(p2, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
        bt.add(p1, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
        val p3 = IpAddressPrefix(6, byteArrayOf("10101100".toInt(2).toByte()))
        bt.add(p3, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
        bt.remove(p3)
        val p2a = IpAddressPrefix(5, byteArrayOf("10100000".toInt(2).toByte()))
        val p2b = IpAddressPrefix(6, byteArrayOf("10101000".toInt(2).toByte()))
        assertEquals(setOf(p2a, p2b).associateWith { ca }, bt.toMap())
        bt.add(p1, ca)
        assertEquals(setOf(p1, p2a, p2b).associateWith { ca }, bt.toMap())
        bt.add(p3, ca)
        assertEquals(setOf(p2).associateWith { ca }, bt.toMap())
        // Add another community
        val cb = setOf(BgpCommunity(3u, 4u))
        bt.add(p3, cb)
        assertEquals(mapOf(p3 to ca + cb, p2a to ca, p2b to ca), bt.toMap())
        bt.add(p2, cb)
        assertEquals(mapOf(p2 to ca + cb), bt.toMap())
        bt.add(p2, ca)
        assertEquals(mapOf(p2 to ca + cb), bt.toMap())
        // One more communities to test with
        val cc = setOf(BgpCommunity(5u, 6u))
        bt.add(p3, cc)
        //  p3  = 101011 -> ca + cb + cc
        //  p2b = 101010 -> ca + cb
        //  p2a = 10100 ->  ca + cb
        assertEquals(mapOf(p3 to ca + cb + cc, p2a to ca + cb, p2b to ca + cb), bt.toMap())
        bt.add(p2b, cc)
        val p2c = IpAddressPrefix(5, byteArrayOf("10101000".toInt(2).toByte()))
        //  p2c = 10101  -> ca + cb + cc
        //  p2a = 10100 ->  ca + cb
        assertEquals(mapOf(p2a to ca + cb, p2c to ca + cb + cc), bt.toMap())
        bt.add(p3, ca + cc)
        assertEquals(mapOf(p2a to ca + cb, p2c to ca + cb + cc), bt.toMap())
    }
}