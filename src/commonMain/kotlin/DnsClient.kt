import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlinx.io.*
import kotlin.random.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
                cache.cleanup(cacheExtraTtl)
            }
        }
        for (datagram in socket.incoming) {
            val response = try {
                datagram.packet.readDnsMessage()
            } catch (e: IOException) {
                // ignore broken datagrams & continue
                log("Broken datagram from ${datagram.address.toLogString()}: $e")
                continue

            }
            if (response.isQuery) {
                log("Ignoring DNS query from ${datagram.address.toLogString()}")
                continue
            }
            val deferred = mutex.withLock { requests.remove(response.id) }
            if (deferred == null) {
                log("Unexpected response from ${datagram.address.toLogString()} for ${response.question} (too late?)")
                continue
            }
            if (verbose) {
                log("Response: $response")
            }
            deferred.complete(response)
        }
    }

    fun resolveFlow(host: String): Flow<ResolveResult> = flow {
        while (true) {
            val result = resolve(host)
            emit(result)
            delay(result.ttl.coerceAtMost(maxResolvePeriod))
        }
    }

    private suspend fun resolve(host: String): ResolveResult {
        val name = host.toDnsName() ?: return ResolveResult.Err("Empty host name")
        val question = DnsQuestion(name, DnsType.A.code, DnsClass.IN.code)
        val flags = DnsFlag.RD.value(1).toUShort()
        val startMark = TimeSource.Monotonic.markNow()
        val response = queryImpl(flags, question)
        if (response?.isResolveResponse(question) == false) return ResolveResult.Err("Unexpected response $response")
        return saveResolveResult(question.qName, response, startMark = startMark)
    }

    private fun DnsQuestion.isResolveQuestion(flags: UShort): Boolean =
        qType == DnsType.A.code && qClass == DnsClass.IN.code && DnsFlag.RD.get(flags) == 1

    private fun DnsMessage.isResolveResponse(question: DnsQuestion): Boolean =
        this.question == question && DnsFlag.RA.get(flags) == 1 && DnsFlag.QR.get(flags) == 1

    suspend fun query(id: UShort, flags: UShort, question: DnsQuestion, src: DnsQuerySource): DnsMessage? {
        val isResolveQuestion = question.isResolveQuestion(flags)
        if (isResolveQuestion) {
            cache.getResolveAnswer(question.qName)?.let { answer ->
                val responseFlags = (flags.toInt() or DnsFlag.RA.value(1) or DnsFlag.QR.value(1)).toUShort()
                logResolveResult(src, question.qName, answer.toResolveResult())
                return DnsMessage(id, responseFlags, question, answer)
            }
        }
        val startMark = TimeSource.Monotonic.markNow()
        val response = queryImpl(flags, question)
        if (isResolveQuestion && response?.isResolveResponse(question) == true) {
            saveResolveResult(question.qName, response, src, delayUpdated = true, startMark = startMark)
        }
        return response?.copy(id = id)
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
        name: DnsName,
        response: DnsMessage?,
        src: DnsQuerySource? = null,
        delayUpdated: Boolean = false,
        startMark: TimeSource.Monotonic.ValueTimeMark? = null
    ): ResolveResult {
        val elapsed = startMark?.elapsedNow() ?: Duration.ZERO
        val result = response.toResolveResult(elapsed)
        if (response == null || result !is ResolveResult.Ok) return result
        cache.putResolveAnswer(name, response.answer, result)
        if (delayUpdated && result.hadUpdatedWildcards) {
            delay(delayUpdatedWildcardResponse)
        }
        if (src != null) {
            logResolveResult(src, name, result)
        }
        return result
    }

    private fun logResolveResult(src: DnsQuerySource, name: DnsName, result: ResolveResult) {
        buildString {
            append(src)
            append(": ")
            append(name)
            append(": ")
            append(result)
        }.let { log(it) }
    }
}

private fun List<DnsAnswer>.toResolveResult(elapsed: Duration = Duration.ZERO): ResolveResult {
    val a = filter { it.aType == DnsType.A.code && it.aClass == DnsClass.IN.code }
    if (a.isEmpty()) return ResolveResult.Err("No IPs found")
    val ttl = minOf { it.ttl } // min of all TTLs, including CNAMEs
    return ResolveResult.Ok(a.map { it.rData as IpAddress }, ttl.toLong().seconds, elapsed)
}

private fun DnsMessage?.toResolveResult(elapsed: Duration): ResolveResult {
    if (this == null) return return ResolveResult.Err("Timeout")
    return answer.toResolveResult(elapsed)
}