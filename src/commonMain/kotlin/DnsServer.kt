import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.io.*

class DnsQuerySource(val protocol: String, val address: SocketAddress) {
    override fun toString(): String = "$protocol $address"
}

class DnsServer(
    val bindAddress: String,
    val selectorManager: SelectorManager
) {
    private val log = Log("DnsServer")

    suspend fun run(onQuery: suspend (src: DnsQuerySource, query: DnsMessage) -> DnsMessage) = coroutineScope<Unit>{
        val socketAddress = InetSocketAddress(bindAddress, DNS_PORT)
        // UDP
        launch {
            val udpSocket = aSocket(selectorManager).udp().bind(socketAddress)
            log("Listening UDP $socketAddress")
            for (queryDatagram in udpSocket.incoming) {
                val query: DnsMessage = try {
                    DnsPacket(queryDatagram.packet.readByteArray()).readDnsMessage()
                } catch (e: IOException) {
                    // ignore broken datagrams & continue
                    log("Broken datagram from ${queryDatagram.address}: $e")
                    continue
                }
                launch {
                    val response = onQuery(DnsQuerySource("UDP", queryDatagram.address), query)
                    val responsePacket = response.buildMessagePacket()
                    val responseDatagram = Datagram(responsePacket, queryDatagram.address)
                    udpSocket.outgoing.send(responseDatagram)
                }
            }
        }
        // TCP
        launch {
            val tcpServerSocket = aSocket(selectorManager).tcp().bind(socketAddress)
            log("Listening TCP $socketAddress")
            while (true) {
                val tcpSocket  = tcpServerSocket.accept()
                launch {
                    val tcpConnection = tcpSocket.connection()
                    val input = tcpConnection.input
                    val output = tcpConnection.output
                    try {
                        coroutineScope {
                            while (true) {
                                val size = input.readShort().toUShort().toInt()
                                val query = DnsPacket(input.readByteArray(size)).readDnsMessage()
                                launch {
                                    val response = onQuery(DnsQuerySource("TCP", tcpSocket.remoteAddress), query)
                                    val responsePacket = response.buildMessagePacket()
                                    val fullPacket = buildPacket {
                                        writeShort(responsePacket.remaining.toUShort().toShort())
                                        writePacket(responsePacket)
                                    }
                                    output.writePacket(fullPacket)
                                }
                            }
                        }
                    } catch (e: IOException) {
                        log("Error in connection from ${tcpSocket.remoteAddress}: $e")
                    }
                    try {
                        tcpSocket.close()
                    } catch (e: IOException) {
                        log("Error while closing connection from ${tcpSocket.remoteAddress}: $e")
                    }
                }
            }
        }
    }
}