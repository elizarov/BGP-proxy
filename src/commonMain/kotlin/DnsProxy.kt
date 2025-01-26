import io.ktor.network.selector.*
import kotlinx.coroutines.*

class DnsProxy(
    private val selectorManager: SelectorManager,
    private val dnsClient: DnsClient,
    private val verbose: Boolean = false
) {
    private val log = Log("DnsProxy")

    suspend fun runDnsProxy() =
        DnsServer(selectorManager).runDnsServer { src, query ->
            if (verbose) {
                log("Request $src: $query")
            }
            if (query.isQuery && query.opCode == 0 && query.rCode == 0 && query.question != null &&
                query.question.qType.isDnsTypeSupported() && query.question.qClass == DnsClass.IN.code
            ) {
                val response = dnsClient.query(query.flags, query.question)
                if (response != null) logDnsProxyResponse(query.question, response)
                response?.copy(id = query.id)
            } else {
                log("Unsupported query: $query")
                DnsMessage(query.id, DnsRCode.NotImplemented.toResponseFlags())
            }
        }

    private fun logDnsProxyResponse(question: DnsQuestion, response: DnsMessage) {
        if (question.qType == DnsType.A.code) {
            buildString {
                append(question.qName)
                append(": ")
                val ips = response.answer.filter { it.aType == DnsType.A.code }.map { it.rData as IpAddress }
                if (ips.isEmpty()) {
                    append("n/a")
                } else {
                    appendListForLog(ips)
                }
            }.let { log(it) }
        }
    }
}

