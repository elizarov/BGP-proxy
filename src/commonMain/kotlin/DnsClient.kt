import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.random.Random

private val DNS_CLIENT_PRIMARY_TIMEOUT = 1000L
private val DNS_CLIENT_REQUEST_TIMEOUT = 10000L

class DnsClient(
    val nameservers: List<String>,
    val selectorManager: SelectorManager
) {
    private val addresses = nameservers.map { InetSocketAddress(it, DNS_PORT) }
    init { require(addresses.isNotEmpty()) }
    private lateinit var socket: BoundDatagramSocket
    private val mutex = Mutex()
    private var nextId = Random.nextInt().toUShort()
    private val requests = HashMap<UShort, CompletableDeferred<DnsMessage>>()
    private val log = Log("DnsClient")

    suspend fun go() {
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

    suspend fun query(flags: UShort, question: DnsQuestion): DnsMessage? {
        val deferred = CompletableDeferred<DnsMessage>()
        var id: UShort
        mutex.withLock {
            id = nextId++
            requests[id] = deferred
        }
        val request = DnsMessage(id, flags, question)
        val requestPacket = request.buildMessagePacket()
        for (i in addresses.indices) {
            val requestDatagram = Datagram(requestPacket, addresses[i])
            socket.outgoing.send(requestDatagram)
            if (i < addresses.lastIndex) {
                withTimeoutOrNull(DNS_CLIENT_PRIMARY_TIMEOUT) { deferred.await() }?.let { return it }
            }
        }
        return withTimeoutOrNull(DNS_CLIENT_REQUEST_TIMEOUT) { deferred.await() }
    }
}