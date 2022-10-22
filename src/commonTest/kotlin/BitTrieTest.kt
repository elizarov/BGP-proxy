import kotlin.test.Test
import kotlin.test.assertEquals

class BitTrieTest {
    @Test
    fun testBasics() {
        val bt = BitTrie()
        assertEquals(emptySet(), bt.toSet())
        val p1 = IpAddressPrefix(10, byteArrayOf("10101110".toInt(2).toByte(), "11000000".toInt(2).toByte()))
        bt.add(p1)
        assertEquals(setOf(p1), bt.toSet())
        val p2 = IpAddressPrefix(4, byteArrayOf("10100000".toInt(2).toByte()))
        bt.add(p2)
        assertEquals(setOf(p2), bt.toSet())
        bt.add(p1)
        assertEquals(setOf(p2), bt.toSet())
        val p3 = IpAddressPrefix(6, byteArrayOf("10101100".toInt(2).toByte()))
        bt.add(p3)
        assertEquals(setOf(p2), bt.toSet())
        bt.remove(p3)
        val p2a = IpAddressPrefix(5, byteArrayOf("10100000".toInt(2).toByte()))
        val p2b = IpAddressPrefix(6, byteArrayOf("10101000".toInt(2).toByte()))
        assertEquals(setOf(p2a, p2b), bt.toSet())
        bt.add(p1)
        assertEquals(setOf(p1, p2a, p2b), bt.toSet())
    }
}