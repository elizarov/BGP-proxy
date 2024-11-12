@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.Monotonic

sealed class ResolveResult {
    data class Ok(val list: List<IpAddressPrefix>) : ResolveResult()
    data class Err(val message: String) : ResolveResult()
}

expect fun resolveHostAddr(host: String): ResolveResult

private val resolveAgain = 1.seconds
private val keepAlive = 1.hours
private val stopAfter = 10.seconds

class HostResolver(coroutineScope: CoroutineScope) {
    private val dispatcher = newSingleThreadContext("Resolver")
    private val scope = coroutineScope + dispatcher
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
            val current = known.keys.sorted()
            if (current != lastResult) {
                val added = current.minus(lastResult)
                val removed = lastResult.minus(current)
                buildString {
                    append(host)
                    append(":")
                    if (added.isNotEmpty()) {
                        append(" (+) ")
                        appendForLog(added)
                    }
                    if (removed.isNotEmpty()) {
                        append(" (-) ")
                        appendForLog(removed)
                    }
                    append(" = ")
                    append(current.size)
                    append(" IPs")
                }.let { log(it) }
                emit(current)
                lastResult = current
            }
            delay(resolveAgain)
        }
    }
}

private fun StringBuilder.appendForLog(list: List<IpAddressPrefix>) {
    val maxN = 2
    append(list.take(maxN).joinToString(", "))
    if (list.size > maxN) {
        append(", ")
        append(list.size - maxN)
        append(" more")
    }
}