import io.ktor.network.selector.*
import kotlinx.coroutines.*

fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) {
        println("Usage: DnsProxy <primary-nameserver> [<secondary-nameserver>]")
        return@runBlocking
    }
    val selectorManager = SelectorManager(createSelectorDispatcher())
    val dnsClient = DnsClient(args.toList(), selectorManager, verbose = true)
    launch { dnsClient.runDnsClient() }
    runDnsProxy(selectorManager, dnsClient, verbose = true)
}

suspend fun runDnsProxy(selectorManager: SelectorManager, dnsClient: DnsClient, verbose: Boolean = false) = coroutineScope {
    val log = Log("DnsProxy")
    DnsServer("0.0.0.0", selectorManager).runDnsServer { src, query ->
        if (verbose) {
            log("$src: $query")
        }
        if (query.isQuery && query.opCode == 0 && query.rCode == 0 && query.question != null &&
            query.question.qType.isDnsTypeSupported() && query.question.qClass == DnsClass.IN.code)
        {
            dnsClient.query(query.flags, query.question)?.copy(id = query.id)
        } else {
            DnsMessage(query.id, DnsRCode.NotImplemented.toResponseFlags())
        }
    }
}