import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val dnsClientPrimaryTimeout = 1.seconds
private val dnsClientSecondaryTimeout = 5.seconds
private val periodicCacheCleanup = 10.seconds
private val cacheExtraTtl = periodicCacheCleanup * 2
private val delayUpdatedWildcardResponse = 200.milliseconds

class DnsClient(
    nameservers: List<String>,
    val selectorManager: SelectorManager,
    val verbose: Boolean = false
) {
    init {
        require(nameservers.isNotEmpty())
    }

    val cache = DnsNameResolveCache()

    private val addresses = nameservers.map { InetSocketAddress(it, DNS_PORT) }

    private lateinit var socket: BoundDatagramSocket
    private val mutex = Mutex()
    private var nextId = Random.nextInt().toUShort()
    private val requests = HashMap<UShort, CompletableDeferred<DnsMessage>>()
    private val log = Log("Dns")

    suspend fun initDnsClient() {
        socket = aSocket(selectorManager).udp().bind()
    }

    suspend fun runDnsClient() = coroutineScope {
        launch {
            while (true) {
                delay(periodicCacheCleanup)
                cache.cleanup()
            }
        }
        for (datagram in socket.incoming) {
            val response = try {
                datagram.packet.readDnsMessage()
            } catch (e: IOException) {
                // ignore broken datagrams & continue
                log("Broken datagram from ${datagram.address}: $e")
                continue

            }
            if (response.isQuery) {
                log("Ignoring DNS query from ${datagram.address}")
                continue
            }
            val deferred = mutex.withLock { requests.remove(response.id) }
            if (deferred == null) {
                log("Unexpected id from ${datagram.address}")
                continue
            }
            if (verbose) {
                log("Response: $response")
            }
            deferred.complete(response)
        }
    }

    fun resolveFlow(host: String): Flow<ResolveResult> = flow {
        val result = resolve(host)
        emit(result)
        delay(result.ttl.coerceAtMost(maxResolvePeriod))
    }

    private suspend fun resolve(host: String): ResolveResult {
        val name = host.toDnsName() ?: return ResolveResult.Err("Empty host name")
        val question = DnsQuestion(name, DnsType.A.code, DnsClass.IN.code)
        val flags = DnsFlag.RD.value(1).toUShort()
        val result = queryImpl(flags, question).toResolveResult()
        saveResolveResult(question, result)
        return result
    }

    suspend fun query(flags: UShort, question: DnsQuestion, src: DnsQuerySource): DnsMessage? {
        val response = queryImpl(flags, question)
        if (question.qType == DnsType.A.code && question.qClass == DnsClass.IN.code &&
            DnsFlag.RD.get(flags) == 1 && response != null)
        {
            val result = response.toResolveResult()
            saveResolveResult(question, result, src, delayUpdated = true)
        }
        return response
    }

    private suspend fun queryImpl(flags: UShort, question: DnsQuestion): DnsMessage? {
        val deferred = CompletableDeferred<DnsMessage>()
        var id: UShort
        mutex.withLock {
            id = nextId++
            requests[id] = deferred
        }
        try {
            val request = DnsMessage(id, flags, question)
            if (verbose) {
                log("Request: $request")
            }
            var response: DnsMessage? = null
            for (i in addresses.indices) {
                val requestPacket = request.buildMessagePacket()
                val requestDatagram = Datagram(requestPacket, addresses[i])
                socket.outgoing.send(requestDatagram)
                if (i < addresses.lastIndex) {
                    response = withTimeoutOrNull(dnsClientPrimaryTimeout) { deferred.await() }
                    if (response != null) break
                }
            }
            if (response == null) {
                response = withTimeoutOrNull(dnsClientSecondaryTimeout) { deferred.await() }
            }
            return response
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { requests.remove(id) }
            }
        }
    }

    private suspend fun saveResolveResult(
        question: DnsQuestion,
        result: ResolveResult,
        src: DnsQuerySource? = null,
        delayUpdated: Boolean = false
    ) {
        if (result !is ResolveResult.Ok) return
        val expiration = TimeSource.Monotonic.markNow() + result.ttl + cacheExtraTtl
        val hadUpdatedWildcards = cache.put(question.qName, DnsNameResolveCache.Entry(result.addresses.toSet(), expiration))
        if (delayUpdated && hadUpdatedWildcards) {
            delay(delayUpdatedWildcardResponse)
        }
        if (src != null) {
            buildString {
                append(src)
                append(": ")
                append(question.qName)
                append(": ")
                append(result)
                if (hadUpdatedWildcards) append(" (*)")
            }.let { log(it) }
        }
    }
}

private fun DnsMessage?.toResolveResult(): ResolveResult {
    if (this == null) return return ResolveResult.Err("Timeout")
    val a = answer.filter { it.aType == DnsType.A.code && it.aClass == DnsClass.IN.code }
    if (a.isEmpty()) return ResolveResult.Err("No IPs found")
    val ttl = answer.minOf { it.ttl } // min of all TTLs, including CNAMEs
    return ResolveResult.Ok(a.map { it.rData as IpAddress }, ttl.toLong().seconds)
}