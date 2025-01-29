import io.ktor.network.selector.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

class DnsProxy(
    val dnsClient: DnsClient,
    private val selectorManager: SelectorManager,
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
                dnsClient.query(query.id, query.flags, query.question, src)
            } else {
                log("Unsupported query: $query")
                DnsMessage(query.id, DnsRCode.NotImplemented.toResponseFlags())
            }
        }

    fun prefixAddressesFlow(hostPrefix: String): Flow<ResolveResult> {
        val name = hostPrefix.toDnsName()
        return flow {
            val channel = Channel<ResolveResult>(Channel.CONFLATED)
            var lastResult = dnsClient.cache.addPrefixFlowChannel(name, channel)
            try {
                while (true) {
                    emit(lastResult)
                    val newResult = withTimeoutOrNull(maxResolvePeriod) { channel.receive() }
                    if (newResult != null) lastResult = newResult
                }
            } finally {
                withContext(NonCancellable) {
                    dnsClient.cache.removePrefixFlowChannel(name, channel)
                }
            }
        }
    }
}

