import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.random.Random

private const val DNS_CLIENT_PRIMARY_TIMEOUT = 1000L
private const val DNS_CLIENT_REQUEST_TIMEOUT = 5000L

sealed class DnsResolveResult {
    data class Ok(val list: List<IpAddress>, val ttl: UInt) : DnsResolveResult()
    data class Err(val message: String) : DnsResolveResult()
}

class DnsClient(
    nameservers: List<String>,
    val selectorManager: SelectorManager,
    val verbose: Boolean = false
) {
    init {
        require(nameservers.isNotEmpty())
    }

    private val addresses = nameservers.map { InetSocketAddress(it, DNS_PORT) }

    private lateinit var socket: BoundDatagramSocket
    private val mutex = Mutex()
    private var nextId = Random.nextInt().toUShort()
    private val requests = HashMap<UShort, CompletableDeferred<DnsMessage>>()
    private val log = Log("DnsClient")

    suspend fun runDnsClient() {
        socket = aSocket(selectorManager).udp().bind()
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
            log(response.toString())
            deferred.complete(response)
        }
    }

    suspend fun resolve(host: String): DnsResolveResult {
        val name = host.toDnsName() ?: return DnsResolveResult.Err("Empty host name")
        val question = DnsQuestion(name, DnsType.A.code, DnsClass.IN.code)
        val flags = DnsFlag.RD.value(1).toUShort()
        val response = query(flags, question) ?: return DnsResolveResult.Err("Timeout")
        val a = response.answer.filter { it.aType == DnsType.A.code && it.aClass == DnsClass.IN.code }
        if (a.isEmpty()) return DnsResolveResult.Err("No A records found")
        val ttl = response.answer.minOf { it.ttl } // min of all TTLs, including CNAMEs
        return DnsResolveResult.Ok(a.map { it.rData as IpAddress }, ttl)
    }

    suspend fun query(flags: UShort, question: DnsQuestion): DnsMessage? {
        val deferred = CompletableDeferred<DnsMessage>()
        var id: UShort
        mutex.withLock {
            id = nextId++
            requests[id] = deferred
        }
        val request = DnsMessage(id, flags, question)
        if (verbose) {
            log("Sending $request")
        }
        val requestPacket = request.buildMessagePacket()
        for (i in addresses.indices) {
            val requestDatagram = Datagram(requestPacket, addresses[i])
            socket.outgoing.send(requestDatagram)
            if (i < addresses.lastIndex) {
                withTimeoutOrNull(DNS_CLIENT_PRIMARY_TIMEOUT) { deferred.await() }?.let { return it }
            }
        }
        withTimeoutOrNull(DNS_CLIENT_REQUEST_TIMEOUT) { deferred.await() }?.let { return it }
        mutex.withLock { requests.remove(id) }
        return null
    }
}