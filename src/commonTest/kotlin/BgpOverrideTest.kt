import kotlin.test.Test
import kotlin.test.assertEquals

class BgpOverrideTest {
    @Test
    fun testParseFile() {
        val (update, errors) = parseOverrideFile("bgp-proxy.override.txt")
        assertEquals(0, errors.size, errors.toString())
        val expectedWithdraw = setOf(
            IpAddressPrefix(16, byteArrayOf(192.toByte(), 168.toByte()))
        )
        val expectedReachable = setOf(
            IpAddressPrefix(32, byteArrayOf(1, 1, 1, 1))
        )
        assertEquals(expectedWithdraw, update.withdrawn)
        assertEquals(expectedReachable, update.reachable)
    }
}