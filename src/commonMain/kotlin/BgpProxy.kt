@file:OptIn(ExperimentalCoroutinesApi::class)

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ResolvedPrefix(val address: IpAddressPrefix, val communities: BgpCommunities)

fun main(args: Array<String>) = runBlocking {
    if (args.size != 3) {
        println("Usage: PGPProxy <local-address> <local-autonomous-system> <config-file>")
        return@runBlocking
    }
    val log = Log("main")
    val localAddress = IpAddress(args[0])
    val autonomousSystem = args[1].toUShort()
    val configFile = args[2]
    val endpoint = BgpEndpoint(localAddress, autonomousSystem)

    log("STARTED with local $endpoint")

    val selectorManager = SelectorManager(createSelectorDispatcher())
    val bgpClientManager = BgpClientManager(this, endpoint, selectorManager)
    val hostResolver = HostResolver(this)
    val bgpConfig = launchConfigLoader(configFile)
    val localCommunities = setOf(BgpCommunity(endpoint.autonomousSystem, 0u))

    // resolve configuration
    val resolvedConfig = bgpConfig.flatMapLatest { overrides ->
        val flows = overrides.map { (op, addressRange) ->
            when (addressRange) {
                is IpAddressPrefix -> flowOf(listOf(
                    BgpConfigItem(op, ResolvedPrefix(addressRange, localCommunities))
                ))
                is HostName -> hostResolver.resolveFlow(addressRange.host).map { list ->
                    list.map { prefix ->
                        BgpConfigItem(op, ResolvedPrefix(prefix, localCommunities))
                    }
                }
                is BgpRemoteSource -> bgpClientManager.resolveClient(addressRange.host).map { bgpState ->
                    bgpState.prefixes.entries.map { (prefix, communities) ->
                        BgpConfigItem(op, ResolvedPrefix(prefix, communities))
                    }
                }
            }
        }
        if (flows.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(flows) { lists -> lists.flatMap { it } }
        }
    }

    // Transform resolved config into bpgState
    val bgpState = resolvedConfig.map { config ->
        val bt = BitTrie()
        for ((op, prefix) in config) when(op) {
            BgpConfigOp.PLUS -> bt.add(prefix.address, prefix.communities)
            BgpConfigOp.MINUS -> bt.remove(prefix.address)
        }
        BgpState(bt.toMap())
    }.stateIn(this, SharingStarted.Eagerly, BgpState())

    // process incoming connections
    val serverSocket = aSocket(selectorManager).tcp().bind(port = BGP_PORT) { reuseAddress = true }
    var connectionCounter = 0
    while (true) {
        val socket = serverSocket.accept()
        val hostname = (socket.remoteAddress as InetSocketAddress).hostname
        launchConnection(Log("${++connectionCounter}-$hostname"), socket, endpoint, bgpState)
    }
}

private fun CoroutineScope.launchConnection(
    log: Log,
    socket: Socket,
    endpoint: BgpEndpoint,
    bgpState: StateFlow<BgpState>
) = launch {
    catchAndLogErrors(log) {
        handleClientConnection(log, socket, bgpState, endpoint)
    }
}

private suspend fun handleClientConnection(log: Log, socket: Socket, bgpState: Flow<BgpState>, endpoint: BgpEndpoint) = coroutineScope {
    log("Accepted connection")
    maintainBgpConnection(log, socket, endpoint) {connection ->
        // parse all messages and ignore all incoming updates
        launch {
            connection.input.parseBgpMessages()
        }
        // send updates
        var lastState = BgpState()
        bgpState.collect { state ->
            val diff = state.diffFrom(lastState)
            log("Sending state: $state ($diff)")
            val withdrawnDeque = ArrayDeque(diff.withdrawn)
            val reachableMap = diff.reachable.toMutableMap()
            var reachableCommunities: BgpCommunities? = null
            var reachableDeque = ArrayDeque<IpAddressPrefix>()
            while (withdrawnDeque.isNotEmpty() || reachableDeque.isNotEmpty() || reachableMap.isNotEmpty()) {
                var remBytes = 1000 // send at most 1k per message
                val withdrawnPacket = composePrefixes(withdrawnDeque, remBytes)
                remBytes -= withdrawnPacket.remaining.toInt()
                if (reachableDeque.isEmpty() && reachableMap.isNotEmpty()) {
                    reachableCommunities = reachableMap.keys.first()
                    reachableDeque = ArrayDeque(reachableMap.remove(reachableCommunities)!!)
                }
                val reachablePacket = composePrefixes(reachableDeque, remBytes)
                val attributesPacket = composeAttributes(
                    if (reachableCommunities != null && reachablePacket.isNotEmpty)
                        createAttributes(endpoint, reachableCommunities) else emptyList()
                )
                connection.output.writeBgpMessage(BgpType.UPDATE) {
                    writeUShort(withdrawnPacket.remaining.toUShort())
                    writePacket(withdrawnPacket)
                    writeUShort(attributesPacket.remaining.toUShort())
                    writePacket(attributesPacket)
                    writePacket(reachablePacket)
                }
            }
            lastState = state
        }
    }
}
