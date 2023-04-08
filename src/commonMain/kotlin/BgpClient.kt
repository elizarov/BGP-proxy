import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

private val initialUpdateDuration = 3.seconds
private val connectionRetryDuration = 3.seconds
private val keepConnectionDuration = 3.seconds

class BgpClientManager(
    private val coroutineScope: CoroutineScope,
    private val endpoint: BgpEndpoint,
    private val selectorManager: SelectorManager
) {
    private val mutex = Mutex()
    private val flows = HashMap<String, Flow<BgpState>>()

    suspend fun clientFlow(remoteAddress: String): Flow<BgpState> = mutex.withLock {
        flows.getOrPut(remoteAddress) {
            newClientFlow(selectorManager, endpoint, remoteAddress)
                .stateIn(coroutineScope, SharingStarted.WhileSubscribed(keepConnectionDuration), BgpState())
        }
    }
}

private fun newClientFlow(selectorManager: SelectorManager, endpoint: BgpEndpoint, remoteAddress: String) = flow {
    val log = Log("uplink-$remoteAddress")
    retryIndefinitely(log, connectionRetryDuration) {
        emitAll(connectToRemote(log, selectorManager, remoteAddress, endpoint))
    }
}

private suspend fun connectToRemote(
    log: Log,
    selectorManager: SelectorManager,
    remoteAddress: String,
    endpoint: BgpEndpoint,
) = channelFlow {
    log("Connecting to $remoteAddress:$BGP_PORT ...")
    val socket = aSocket(selectorManager).tcp().connect(remoteAddress, BGP_PORT)
    try {
        maintainBgpConnection(log, socket, endpoint) { connection ->
            val incomingUpdates = parseIncomingUpdates(log, connection.input)
            // wait for initial update duration to make sure the full state is gathered
            delay(initialUpdateDuration)
            // constantly emit all incoming state into the state flow
            log("Start serving updates from the new connection")
            for (update in incomingUpdates) {
                send(update)
            }
        }
    } finally {
        log("Closing connection to $remoteAddress:$BGP_PORT")
        socket.close()
    }
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
                    log("Updated state: $bgpState ($update)")
                    send(bgpState)
                }
            }
        )
    }
