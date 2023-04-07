import kotlin.test.Test
import kotlin.test.assertEquals

class BgpOverrideTest {
    @Test
    fun testParseFile() {
        val (items, errors) = parseConfigFile("bgp-proxy.cfg")
        assertEquals(0, errors.size, errors.toString())
        val expectedItems = listOf(
            BgpConfigItem(BgpConfigOp.PLUS, BgpRemoteSource("antifilter.download")),
            BgpConfigItem(BgpConfigOp.MINUS, IpAddressPrefix(16, byteArrayOf(192.toByte(), 168.toByte()))),
            BgpConfigItem(BgpConfigOp.PLUS, IpAddressPrefix(32, byteArrayOf(1, 1, 1, 1))),
            BgpConfigItem(BgpConfigOp.PLUS, HostName("pbs.twimg.com")),
            BgpConfigItem(BgpConfigOp.MINUS, HostName("qwerty"))
        )
        assertEquals(expectedItems, items)
    }
}