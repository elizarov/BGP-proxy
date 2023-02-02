@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

const val BGP_PORT = 179
const val BGP_VERSION: Byte = 4
const val HOLD_TIME: UShort = 240u

val connectionRetryDuration = 3.seconds
val initialUpdateDuration = 3.seconds
val fileRescanDuration = 1.seconds

val BGP_MARKER = UByteArray(16) { 0xffu }

enum class BgpType(val tag: Byte) { OPEN(1), UPDATE(2), NOTIFICATION(3), KEEP_ALIVE(4) }

fun main(args: Array<String>) = runBlocking {
    if (args.size !in 3..4) {
        println("Usage: PGPProxy <remote-uplink-address> <local-address> <local-autonomous-system> [<override-file>]")
        return@runBlocking
    }
    val remoteAddress = args[0]
    val localAddress = IpAddress(args[1])
    val autonomousSystem = args[2].toUShort()
    val overrideFile = args.getOrNull(3)
    val endpoint = BgpEndpoint(localAddress, autonomousSystem)
    val selectorManager = SelectorManager(createSelectorDispatcher())
    val bgpRemoteState = MutableStateFlow(BgpState())
    val bgpOverrides = MutableStateFlow(emptyList<BgpOverride<AddressRange>>())
    if (overrideFile != null) {
        val log = Log("override")
        // launch file tracker
        launch {
            log("Started tracking override file $overrideFile")
            var prevErrors = emptyList<String>()
            retryIndefinitely(log, fileRescanDuration) {
                val (overrides, errors) = parseOverrideFile(overrideFile)
                if (errors != prevErrors) {
                    errors.forEach { log("ERROR: $it") }
                    prevErrors = errors
                }
                bgpOverrides.emit(overrides)
            }
        }
        // log override changes
        launch {
            bgpOverrides.collect { overrides ->
                log("overriding ${overrides.size} address ranges " +
                        "(+${overrides.count { it.op == BgpOverrideOp.PLUS}} " +
                        "-${overrides.count { it.op == BgpOverrideOp.MINUS}})")
            }
        }
    }
    // launch client
    launch {
        val log = Log("uplink")
        log("Proxy started with local $endpoint")
        retryIndefinitely(log, connectionRetryDuration) {
            connectToRemote(log, selectorManager, remoteAddress, endpoint, bgpRemoteState)
        }
    }
    // resolve all configured overrides
    val resolvedOverrides = bgpOverrides.flatMapLatest { overrides ->
        val flows = overrides.map { (op, addressRange) ->
            when (addressRange) {
                is IpAddressPrefix -> flowOf(listOf(BgpOverride(op, addressRange)))
                is HostName -> resolveFlow(addressRange.host).map { list ->
                    list.map { prefix -> BgpOverride(op, prefix) }
                }
            }
        }
        combine(flows) { lists -> lists.flatMap { it } }
    }
    // combine remote & resolved overrides
    val bgpState = combine(bgpRemoteState, resolvedOverrides) { remote, overrides ->
        remote.applyOverrides(overrides)
    }.stateIn(this)
    // process incoming connections
    val serverSocket = aSocket(selectorManager).tcp().bind(port = BGP_PORT) { reuseAddress = true }
    var connectionCounter = 0
    while (true) {
        val socket = serverSocket.accept()
        val hostname = (socket.remoteAddress as InetSocketAddress).hostname
        val log = Log("${++connectionCounter}-$hostname")
        launch {
            catchAndLogErrors(log) {
                handleClientConnection(log, socket, bgpState, endpoint)
            }
        }
    }
}

suspend fun handleClientConnection(log: Log, socket: Socket, bgpState: Flow<BgpState>, endpoint: BgpEndpoint) = coroutineScope {
    log("Accepted connection")
    maintainBgpConnection(log, socket, endpoint) {connection ->
        // parse all messages and ignore all incoming updates
        launch {
            connection.input.parseBgpMessages()
        }
        // send updates
        var lastState = BgpState()
        bgpState.collect { state ->
            val update = state.diffFrom(lastState)
            log("Sending updated state: $state ($update)")
            val withdrawn = update.withdrawn.toMutableSet()
            val reachable = update.reachable.toMutableSet()
            while (withdrawn.isNotEmpty() || reachable.isNotEmpty()) {
                var remBytes = 1000 // send at most 1k per message
                val withdrawnPacket = composePrefixes(withdrawn, remBytes)
                remBytes -= withdrawnPacket.remaining.toInt()
                val reachablePacket = composePrefixes(reachable, remBytes)
                val attributes = state.attributes
                connection.output.writeBgpMessage(BgpType.UPDATE) {
                    writeUShort(withdrawnPacket.remaining.toUShort())
                    writePacket(withdrawnPacket)
                    writeUShort(attributes.bytes.size.toUShort())
                    writeFully(attributes.bytes)
                    writePacket(reachablePacket)
                }
            }
            lastState = state
        }
    }
}

suspend fun connectToRemote(
    log: Log,
    selectorManager: SelectorManager,
    remoteAddress: String,
    endpoint: BgpEndpoint,
    bgpRemoteState: MutableStateFlow<BgpState>
) {
    log("Connecting to $remoteAddress ...")
    val socket = aSocket(selectorManager).tcp().connect(remoteAddress, BGP_PORT)
    maintainBgpConnection(log, socket, endpoint) { connection ->
        val updates = parseIncomingUpdates(log, connection.input)
        // wait for initial update duration to make sure the full state is gathered
        delay(initialUpdateDuration)
        // constantly emit all incoming state into the state flow
        log("Start serving updates from the new connection")
        bgpRemoteState.emitAll(updates)
    }
}

private suspend fun maintainBgpConnection(
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

@OptIn(ExperimentalCoroutinesApi::class)
private fun CoroutineScope.parseIncomingUpdates(
    log: Log,
    input: ByteReadChannel
): ReceiveChannel<BgpState> =
    produce(capacity = Channel.CONFLATED) {
        var bgpState = BgpState()
        input.parseBgpMessages(
            onUpdate = {
                val update = readBpgUpdate()
                val newState = bgpState.apply(update)
                if (newState != bgpState) {
                    bgpState = newState
                    log("Received updated state: $bgpState ($update)")
                    send(bgpState)
                }
            }
        )
    }

private suspend fun ByteReadChannel.parseBgpMessages(onUpdate: suspend ByteReadPacket.() -> Unit = {}) {
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
    val attributes = BgpAttributes(readBytes(totalPathAttributeLength.toInt()))
    val reachable = readPrefixes(remaining.toInt())
    return BgpUpdate(withdrawn, reachable, attributes)
}

class IpAddress(val bytes: UByteArray) {
    constructor(s: String) : this(
        s.split(".").also {
            check(it.size == 4) { "Address must be 'xxx.xxx.xxx.xxx' but found: $s" }
        }.map { it.toUByte() }.toUByteArray()
    )

    override fun toString(): String = bytes.joinToString(".")
}

fun IpAddress(bytes: ByteArray) = IpAddress(bytes.toUByteArray())

data class BgpEndpoint(
    val address: IpAddress,
    val autonomousSystem: UShort
) {
    override fun toString(): String = "id=$address (AS=$autonomousSystem)"
}

suspend fun retryIndefinitely(log: Log, delay: Duration, action: suspend () -> Unit) {
    repeatEvery(delay) {
        catchAndLogErrors(log) {
            action()
        }
    }
}

private inline fun catchAndLogErrors(log: Log, action: () -> Unit) {
    try {
        action()
    } catch (e: Throwable) {
        log("Error: ${e.message}")
        if (e !is ClosedReceiveChannelException && e !is IllegalStateException && e !is IOException) {
            e.printStackTrace()
        }
    }
}

suspend fun repeatEvery(delay: Duration, action: suspend () -> Unit) {
    while (true) {
        action()
        delay(delay)
    }
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
    for (i in 0..3) writeUByte(localAddress.bytes[i])
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

class Log(private val id: String) {
    operator fun invoke(msg: String) {
        println("${currentTimestamp()} [$id]: $msg")
    }
}