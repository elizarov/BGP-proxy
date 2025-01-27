import io.ktor.network.selector.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class DnsProxy(
    private val selectorManager: SelectorManager,
    private val dnsClient: DnsClient,
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

    fun prefixAddressesFlow(hostPrefix: String): Flow<Set<IpAddress>> {
        val name = hostPrefix.toDnsName()
        return callbackFlow {
            val channel = this
            mutex.withLock {
                cache.addPrefixFlowChannel(name, channel) }
            try {
                awaitClose()
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { cache.removePrefixFlowChannel(name, channel) }
                }
            }
        }
    }

    private suspend fun saveResponse(question: DnsQuestion, response: DnsMessage) {
        if (question.qType != DnsType.A.code) return
        val ips = response.answer.filter { it.aType == DnsType.A.code }.mapTo(HashSet()) { it.rData as IpAddress }
        var ttl: UInt? = null
        var updated: Boolean = true
//        if (ips.isNotEmpty()) {
//            ttl = response.answer.minOf { it.ttl }
//            val expiration = TimeSource.Monotonic.markNow() + ttl.toLong().seconds
//            updated = mutex.withLock { cache.put(question.qName, DnsNameResolveCache.Entry(ips, expiration)) }
//        }
        if (updated) {
            buildString {
                append(question.qName)
                append(": ")
                if (ips.isEmpty()) {
                    append("n/a")
                } else {
                    appendListForLog(ips)
                    append(" TTL:$ttl")
                }
            }.let { log(it) }
        }
    }
}

