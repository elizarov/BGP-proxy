import kotlin.test.Test
import kotlin.test.assertEquals

class BgpOverrideTest {
    @Test
    fun testParseFile() {
        val (items, errors) = parseConfigFile("bgp-proxy.cfg")
        assertEquals(0, errors.size, errors.toString())
        val expectedItems = listOf(
            ConfigItem(ConfigOp.PLUS, BgpRemoteSource("antifilter.download")),
            ConfigItem(ConfigOp.MINUS, IpAddressPrefix(16, byteArrayOf(192.toByte(), 168.toByte()))),
            ConfigItem(ConfigOp.PLUS, IpAddressPrefix(32, byteArrayOf(1, 1, 1, 1))),
            ConfigItem(ConfigOp.PLUS, DnsHostName("pbs.twimg.com")),
            ConfigItem(ConfigOp.MINUS, DnsHostName("qwerty"))
        )
        assertEquals(expectedItems, items)
    }
}