import io.ktor.network.selector.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val delayUpdatedResponse = 200.milliseconds

class DnsProxy(
    val dnsClient: DnsClient,
    private val selectorManager: SelectorManager,
    private val verbose: Boolean = false
) {
    private val log = Log("DnsProxy")
    private val mutex = Mutex()
    private val cache = DnsNameResolveCache()

    suspend fun runDnsProxy() =
        DnsServer(selectorManager).runDnsServer { src, query ->
            if (verbose) {
                log("Request $src: $query")
            }
            if (query.isQuery && query.opCode == 0 && query.rCode == 0 && query.question != null &&
                query.question.qType.isDnsTypeSupported() && query.question.qClass == DnsClass.IN.code
            ) {
                val response = dnsClient.query(query.flags, query.question)
                if (response != null) saveResponse(query.question, response)
                response?.copy(id = query.id)
            } else {
                log("Unsupported query: $query")
                DnsMessage(query.id, DnsRCode.NotImplemented.toResponseFlags())
            }
        }

    fun prefixAddressesFlow(hostPrefix: String): Flow<ResolveResult> {
        val name = hostPrefix.toDnsName()
        return flow {
            val channel = Channel<ResolveResult>(Channel.CONFLATED)
            val initial = mutex.withLock {
                cache.addPrefixFlowChannel(name, channel)
            }
            emit(initial)
            try {
                while (true) {
                    val result = withTimeoutOrNull(maxResolvePeriod) { channel.receive() }
                    if (result == null) {
                        emit(ResolveResult.Periodic)
                    } else {
                        emit(result)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { cache.removePrefixFlowChannel(name, channel) }
                }
            }
        }
    }

    private suspend fun saveResponse(question: DnsQuestion, response: DnsMessage) {
        if (question.qType != DnsType.A.code) return
        val ips = response.answer
            .filter { it.aType == DnsType.A.code }
            .mapTo(HashSet()) { it.rData as IpAddress }
        var ttl: UInt? = null
        var updated: List<Pair<SendChannel<ResolveResult>, ResolveResult>>? = null
        if (ips.isNotEmpty()) {
            ttl = response.answer.minOf { it.ttl }
            val expiration = TimeSource.Monotonic.markNow() + ttl.toLong().seconds
            updated = mutex.withLock {
                cache.put(question.qName, DnsNameResolveCache.Entry(ips, expiration))
            }
        }
        if (updated == null) return
        for ((channel, updatedIps) in updated) {
            channel.send(updatedIps)
        }
        buildString {
            append(question.qName)
            append(": ")
            if (ips.isEmpty()) {
                append("n/a")
            } else {
                appendListForLog(ips)
                append(" TTL:$ttl")
            }
            if (updated.isNotEmpty()) {
                append(" (*)")
            }
        }.let { log(it) }
        if (updated.isNotEmpty()) {
            delay(delayUpdatedResponse)
        }
    }
}

