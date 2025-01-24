import io.ktor.network.selector.SelectorManager

class DnsClient(
    val nameservers: List<String>,
    val selectorManager: SelectorManager
) {

}