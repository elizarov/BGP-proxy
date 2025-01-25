import io.ktor.network.selector.*
import kotlinx.coroutines.*

suspend fun runDnsProxy(selectorManager: SelectorManager, dnsClient: DnsClient, verbose: Boolean = false) = coroutineScope {
    val log = Log("DnsProxy")
    DnsServer(selectorManager).runDnsServer { src, query ->
        if (verbose) {
            log("Request $src: $query")
        }
        if (query.isQuery && query.opCode == 0 && query.rCode == 0 && query.question != null &&
            query.question.qType.isDnsTypeSupported() && query.question.qClass == DnsClass.IN.code)
        {
            val response = dnsClient.query(query.flags, query.question)
            if (response != null) {
                buildString {
                    append(query.question.qName)
                    append(": ")
                    val ips = response.answer.filter { it.aType == DnsType.A.code }.map { it.rData as IpAddress  }
                    if (ips.isEmpty()) {
                        append("n/a")
                    } else {
                        appendListForLog(ips)
                    }
                }.let { log(it) }
            }
            response?.copy(id = query.id)
        } else {
            log("Unsupported query: $query")
            DnsMessage(query.id, DnsRCode.NotImplemented.toResponseFlags())
        }
    }
}