import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

const val BGP_PORT = 179
const val BGP_VERSION: Byte = 4
const val HOLD_TIME: UShort = 240u

val connectionRetryDuration = 3.seconds
val initialUpdateDuration = 3.seconds
val fileRescanDuration = 1.seconds

val BGP_MARKER = UByteArray(16) { 0xffu }

enum class BgpType(val tag: Byte) { OPEN(1), UPDATE(2), NOTIFICATION(3), KEEP_ALIVE(4) }

data class BgpEndpoint(
    val address: IpAddress,
    val autonomousSystem: UShort
) {
    override fun toString(): String = "AS$autonomousSystem $address"
}

suspend fun maintainBgpConnection(
    log: Log,
    socket: Socket,
    endpoint: BgpEndpoint,
    onActive: suspend CoroutineScope.(Connection) -> Unit
) = coroutineScope {
    val connection = socket.connection()
    val output = connection.output
    // send OPEN
    output.writeBgpMessage(BgpType.OPEN) {
        writeByte(BGP_VERSION)
        writeUShort(endpoint.autonomousSystem)
        writeUShort(HOLD_TIME)
        write(endpoint.address)
        writeByte(0) // optional parameters
    }
    // receive OPEN
    val input = connection.input
    var commonHoldTime = HOLD_TIME
    input.readBgpMessage { type ->
        check(type == BgpType.OPEN) { "The first message must be OPEN" }
        val version = readByte()
        check(version == BGP_VERSION) { "Expected version $BGP_VERSION but got: $version" }
        val remoteAs = readUShort()
        val holdTime = readUShort()
        val remoteId = IpAddress(readBytes(4))
        log("Connected to ${BgpEndpoint(remoteId, remoteAs)}")
        if (holdTime > 0u) commonHoldTime = minOf(HOLD_TIME, holdTime)
    }
    // send keep alive right now and every 1/3 of hold time (see specs)
    launch(start = CoroutineStart.UNDISPATCHED) {
        repeatEvery(commonHoldTime.toInt().seconds / 3) {
            output.writeBgpMessage(BgpType.KEEP_ALIVE) {}
        }
    }
    // connection is active now
    onActive(connection)
}

suspend fun ByteReadChannel.parseBgpMessages(onUpdate: suspend ByteReadPacket.() -> Unit = {}) {
    while (true) {
        readBgpMessage { type ->
            when (type) {
                BgpType.OPEN -> error("BGP: Unexpected OPEN")
                BgpType.UPDATE -> onUpdate()
                BgpType.NOTIFICATION -> {
                    val errorCode = readUByte()
                    val errorSubcode = readUByte()
                    error("NOTIFICATION with error code=$errorCode, subcode=$errorSubcode")
                }
                BgpType.KEEP_ALIVE -> {}
            }
        }
    }
}

fun ByteReadPacket.readBpgUpdate(): BgpUpdate {
    val withdrawnRoutesLen = readUShort()
    val withdrawn = readPrefixes(withdrawnRoutesLen.toInt())
    val totalPathAttributeLength = readUShort()
    val attributes = readAttributes(totalPathAttributeLength.toInt())
    val reachable = readPrefixes(remaining.toInt())
    return BgpUpdate(withdrawn, reachable, attributes)
}

fun BytePacketBuilder.buildBgpPacket(type: BgpType, block: BytePacketBuilder.() -> Unit) {
    val packet = buildPacket(block)
    writeFully(BGP_MARKER)
    writeShort((packet.remaining.toInt() + BGP_MARKER.size + 3).toShort())
    writeByte(type.tag)
    writePacket(packet)
}

suspend fun ByteWriteChannel.writeBgpMessage(type: BgpType, block: BytePacketBuilder.() -> Unit) {
    writePacket {
        buildBgpPacket(type, block)
    }
    flush()
}

fun BytePacketBuilder.write(localAddress: IpAddress) {
    for (i in 0..3) writeByte(localAddress.bytes[i])
}

suspend fun ByteReadChannel.readBgpMessage(block: suspend ByteReadPacket.(BgpType) -> Unit) {
    val marker = ByteArray(BGP_MARKER.size)
    readFully(marker)
    check(marker.toUByteArray().contentEquals(BGP_MARKER)) { "Wrong marker in incoming stream: ${marker.toHexString()} " }
    val length = readShort()
    check(length in 19..4096) { "Wrong length in incoming stream: $length" }
    val n = length - BGP_MARKER.size - 3
    val typeTag = readByte()
    val type = BgpType.values().find { it.tag == typeTag }
    check(type != null) { "Wrong type in incoming stream: $typeTag" }
    readPacket(n).use {
        block(it, type)
    }
}