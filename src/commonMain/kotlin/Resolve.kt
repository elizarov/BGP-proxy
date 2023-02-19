@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource.*

sealed class ResolveResult {
    data class Ok(val list: List<IpAddressPrefix>) : ResolveResult()
    data class Err(val message: String) : ResolveResult()
}

expect fun resolveHostAddr(host: String): ResolveResult

private val resolveAgain = 1.seconds
private val keepAlive = 1.hours
private val stopAfter = 10.seconds

class HostResolver {
    private val dispatcher = newSingleThreadContext("Resolver")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    // mutations are protected with mutex
    private val flows = HashMap<String, Flow<List<IpAddressPrefix>>>()
    private val log = Log("resolve")

    suspend fun resolveFlow(host: String): Flow<List<IpAddressPrefix>> = mutex.withLock {
        flows.getOrPut(host) {
            newResolveFlow(host).stateIn(scope, SharingStarted.WhileSubscribed(stopAfter), emptyList())
        }
    }

    private fun newResolveFlow(host: String) = flow {
        val known = LinkedHashMap<IpAddressPrefix, Monotonic.ValueTimeMark>()
        var lastResult = emptyList<IpAddressPrefix>()
        var lastError: String? = null
        while (true) {
            val result = resolveHostAddr(host)
            val now = Monotonic.markNow()
            when (result) {
                is ResolveResult.Err -> {
                    if (result.message != lastError) {
                        lastError = result.message
                        log("$host: $lastError")
                    }
                }
                is ResolveResult.Ok -> for (address in result.list) known[address] = now
            }
            known.values.removeAll { mark -> mark.elapsedNow() > keepAlive }
            val current = known.keys.toList()
            if (current != lastResult) {
                log("$host -> ${current.joinToString(", ")}")
                emit(current)
                lastResult = current
            }
            delay(resolveAgain)
        }
    }
}