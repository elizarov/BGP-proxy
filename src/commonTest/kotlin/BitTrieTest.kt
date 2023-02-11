import kotlin.test.Test
import kotlin.test.assertEquals

class BitTrieTest {
    @Test
    fun testBasics() {
        val bt = BitTrie()
        val ca = BgpCommunity(1u, 2u)
        val cas = setOf(ca)
        assertEquals(emptyMap(), bt.toMap())
        val p1 = IpAddressPrefix(10, byteArrayOf("10101110".toInt(2).toByte(), "11000000".toInt(2).toByte()))
        bt.add(p1, ca)
        assertEquals(setOf(p1).associateWith { cas }, bt.toMap())
        val p2 = IpAddressPrefix(4, byteArrayOf("10100000".toInt(2).toByte()))
        bt.add(p2, ca)
        assertEquals(setOf(p2).associateWith { cas }, bt.toMap())
        bt.add(p1, ca)
        assertEquals(setOf(p2).associateWith { cas }, bt.toMap())
        val p3 = IpAddressPrefix(6, byteArrayOf("10101100".toInt(2).toByte()))
        bt.add(p3, ca)
        assertEquals(setOf(p2).associateWith { cas }, bt.toMap())
        bt.remove(p3)
        val p2a = IpAddressPrefix(5, byteArrayOf("10100000".toInt(2).toByte()))
        val p2b = IpAddressPrefix(6, byteArrayOf("10101000".toInt(2).toByte()))
        assertEquals(setOf(p2a, p2b).associateWith { cas }, bt.toMap())
        bt.add(p1, ca)
        assertEquals(setOf(p1, p2a, p2b).associateWith { cas }, bt.toMap())
        bt.add(p3, ca)
        assertEquals(setOf(p2).associateWith { cas }, bt.toMap())
        // Add another community
        val cb = BgpCommunity(3u, 4u)
        val cbs = setOf(cb)
        bt.add(p3, cb)
        assertEquals(mapOf(p2 to cas, p3 to cas + cbs), bt.toMap())
        bt.add(p2, cb)
        assertEquals(mapOf(p2 to cas + cbs), bt.toMap())
        bt.add(p2, ca)
        assertEquals(mapOf(p2 to cas + cbs), bt.toMap())
        // One more communities to test with
        val cc = BgpCommunity(5u, 6u)
        val ccs = setOf(cc)
        bt.add(p3, cc)
        //  p2  = 1010   -> ca + cb
        //  p3  = 101011 -> ca + cb + cc
        assertEquals(mapOf(p3 to cas + cbs + ccs, p2 to cas + cbs), bt.toMap())
        bt.add(p2b, cc)
        val p2c = IpAddressPrefix(5, byteArrayOf("10101000".toInt(2).toByte()))
        //  p2  = 10100  -> ca + cb
        //  p2c = 10101  -> ca + cb + cc
        assertEquals(mapOf(p2 to cas + cbs, p2c to cas + cbs + ccs), bt.toMap())
    }
}