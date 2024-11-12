import io.ktor.utils.io.core.remaining
import kotlin.test.Test
import kotlin.test.assertEquals

class BgpAttributesTest {
    @Test
    fun testAttributesParseCompose() {
        val autonomousSystem: UShort = 64512u
        val endpoint = BgpEndpoint(IpAddress("196.158.1.1"), autonomousSystem)
        val communities = setOf(BgpCommunity(autonomousSystem, 1u))
        val attributes0 = createAttributes(endpoint, communities)
        assertEquals("IGP AS64512 196.158.1.1 [64512:1]", attributes0.joinToString(" "))
        val packet = composeAttributes(attributes0)
        val attributes1 = packet.readAttributes(packet.remaining.toInt())
        assertEquals(attributes0, attributes1)
    }
}