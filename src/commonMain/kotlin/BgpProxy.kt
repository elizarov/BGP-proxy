@file:OptIn(ExperimentalCoroutinesApi::class)

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.writeUShort

data class ResolvedConfigPrefixes(
    val op: ConfigOp,
    val prefixes: Map<IpAddressPrefix, BgpCommunities>,
    val bgpSource: Boolean = false
)

fun main(args: Array<String>) = runBlocking {
    if (args.size < 3) {
        println("Usage: PGPProxy <local-address> <local-autonomous-system> <config-file> [<nameservers>]")
        return@runBlocking
    }
    val log = Log("main")
    val localAddress = IpAddress(args[0])
    val autonomousSystem = args[1].toUShort()
    val configFile = args[2]
    val nameservers = args.drop(3).toList()
    val endpoint = BgpEndpoint(localAddress, autonomousSystem)

    log("STARTED with local $endpoint")

    val selectorManager = SelectorManager(createSelectorDispatcher())
    val dnsClient = if (nameservers.isEmpty()) null else DnsClient(nameservers, selectorManager, true)
    val bgpClientManager = BgpClientManager(this, endpoint, selectorManager)
    val hostResolver = HostResolver(this, dnsClient)
    val localCommunities = setOf(BgpCommunity(endpoint.autonomousSystem, 0u))

    if (dnsClient != null) launch { dnsClient.runDnsClient() }

    // resolve configuration and transform resolved config into BpgState
    val bgpState = launchConfigLoader(configFile).flatMapLatest { config ->
        val flows = config.map { (op, addressRange) ->
            // Resolve configuration items based on their type
            when (addressRange) {
                is IpAddressPrefix -> flowOf(
                    ResolvedConfigPrefixes(op, mapOf(addressRange to localCommunities))
                )
                is DnsHostName -> hostResolver.resolveFlow(addressRange.host).map { list ->
                    ResolvedConfigPrefixes(op, list.associateWith { localCommunities })
                }
                is BgpRemoteSource -> bgpClientManager.clientFlow(addressRange.host).map { bgpState ->
                    ResolvedConfigPrefixes(op, bgpState.prefixes, bgpSource = true)
                }
            }
        }
        if (flows.isEmpty()) {
            flowOf(BgpState())
        } else {
            // Combine all configuration items into a single state
            combine(flows) { list ->
                val bt = BitTrie()
                for ((op, prefixes, bgpSource) in list) {
                    for ((prefix, communities) in prefixes) {
                        when (op) {
                            ConfigOp.PLUS -> if (bgpSource) bt.set(prefix, communities) else bt.add(prefix, communities)
                            ConfigOp.MINUS -> bt.remove(prefix)
                        }
                    }
                }
                BgpState(bt.toMap())
            }
        }
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
            connection.input.parseBgpMessages {}
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
                    if (reachableCommunities != null && !reachablePacket.exhausted())
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
