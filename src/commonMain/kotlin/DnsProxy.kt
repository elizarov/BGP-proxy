import io.ktor.network.selector.*
import kotlinx.coroutines.*

fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) {
        println("Usage: DnsProxy <primary-nameserver> [<secondary-nameserver>]")
        return@runBlocking
    }
    val selectorManager = SelectorManager(createSelectorDispatcher())
    val client = DnsClient(args.toList(), selectorManager)
    val log = Log("DnsProxy")
    DnsServer("0.0.0.0", selectorManager).run { src, query ->
        log("$src: $query")
        DnsMessage(query.id, DnsRCode.NotImplemented.toResponseFlags())
    }
}