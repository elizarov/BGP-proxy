import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.io.*

class DnsQuerySource(val protocol: String, val address: InetSocketAddress) {
    override fun toString(): String = "$protocol/${address.hostname}"
}

class DnsServer(
    val selectorManager: SelectorManager
) {
    private val log = Log("DnsServer")

    suspend fun runDnsServer(onQuery: suspend (src: DnsQuerySource, query: DnsMessage) -> DnsMessage?) = coroutineScope<Unit>{
        val socketAddress = InetSocketAddress("0.0.0.0", DNS_PORT)
        // UDP
        launch {
            val udpSocket = retryOperation(log, "Listening UDP port $DNS_PORT") {
                aSocket(selectorManager).udp().bind(socketAddress)
            }
            for (queryDatagram in udpSocket.incoming) {
                val query: DnsMessage = try {
                    queryDatagram.packet.readDnsMessage()
                } catch (e: IOException) {
                    // ignore broken datagrams & continue
                    log("Broken datagram from ${queryDatagram.address.toLogString()}: $e")
                    continue
                }
                launch {
                    val response = onQuery(DnsQuerySource("UDP", queryDatagram.address as InetSocketAddress), query)
                    if (response != null) {
                        val responsePacket = response.buildMessagePacket()
                        val responseDatagram = Datagram(responsePacket, queryDatagram.address)
                        udpSocket.outgoing.send(responseDatagram)
                    }
                }
            }
        }
        // TCP
        launch {
            val tcpServerSocket = retryOperation(log, "Listening TCP port $DNS_PORT") {
                aSocket(selectorManager).tcp().bind(port = DNS_PORT)
            }
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
                                val query = input.readByteArray(size).readDnsMessage()
                                launch {
                                    val response = onQuery(DnsQuerySource("TCP", tcpSocket.remoteAddress as InetSocketAddress), query)
                                    if (response != null) {
                                        val responsePacket = response.buildMessagePacket()
                                        val fullPacket = buildPacket {
                                            writeShort(responsePacket.remaining.toUShort().toShort())
                                            writePacket(responsePacket)
                                        }
                                        output.writePacket(fullPacket)
                                        output.flush()
                                    }
                                }
                            }
                        }
                    } catch (e: IOException) {
                        log("Error in connection from ${tcpSocket.remoteAddress.toLogString()}: $e")
                    }
                    try {
                        tcpSocket.close()
                    } catch (e: IOException) {
                        log("Error while closing connection from ${tcpSocket.remoteAddress.toLogString()}: $e")
                    }
                }
            }
        }
    }
}