import kotlin.test.Test
import kotlin.test.assertEquals

class BgpOverrideTest {
    @Test
    fun testParseFile() {
        val (overrides, errors) = parseOverrideFile("bgp-proxy.override.txt")
        assertEquals(0, errors.size, errors.toString())
        val expectedOverrides = listOf(
            BgpOverride(BgpOverrideOp.MINUS, IpAddressPrefix(16, byteArrayOf(192.toByte(), 168.toByte()))),
            BgpOverride(BgpOverrideOp.PLUS, IpAddressPrefix(32, byteArrayOf(1, 1, 1, 1))),
            BgpOverride(BgpOverrideOp.PLUS, HostName("pbs.twimg.com"))
        )
        assertEquals(expectedOverrides, overrides)
    }
}