@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlin.time.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource.*

private val keepAlive = 1.hours
private val stopAfter = 10.seconds
private val nativeResolveTtl = 1.seconds
private val resolveAgainOnError = 3.seconds

val maxResolvePeriod = 1.minutes

sealed class ResolveResult {
    abstract val ttl: Duration
    data class Ok(val addresses: Collection<IpAddress>, override val ttl: Duration = nativeResolveTtl) : ResolveResult()
    data class Err(val message: String, override val ttl: Duration = resolveAgainOnError) : ResolveResult()
    data object Periodic : ResolveResult() { override val ttl: Duration = maxResolvePeriod }
}

fun interface ResolverFactory {
    fun resolveFlow(host: String): Flow<ResolveResult>
}

// Resolves and keep the most recent IP addresses seen
class HostResolver(
    private val coroutineScope: CoroutineScope,
    private val resolverFactory: ResolverFactory
) {
    private val mutex = Mutex()
    // mutations are protected with mutex
    private val flows = HashMap<String, Flow<Set<IpAddress>>>()
    private val log = Log("resolve")

    suspend fun resolveFlow(host: String): Flow<Set<IpAddress>> = mutex.withLock {
        flows.getOrPut(host) {
            newResolveFlow(host).stateIn(coroutineScope, SharingStarted.WhileSubscribed(stopAfter), emptySet())
        }
    }

    private fun newResolveFlow(host: String): Flow<Set<IpAddress>> {
        val resolver = resolverFactory.resolveFlow(host)
        return flow {
            val known = LinkedHashMap<IpAddress, Monotonic.ValueTimeMark>()
            var lastResult = emptySet<IpAddress>()
            var lastError: String? = null
            resolver.collect { result ->
                val now = Monotonic.markNow()
                when (result) {
                    is ResolveResult.Err -> {
                        if (result.message != lastError) {
                            lastError = result.message
                            log("$host: $lastError")
                        }
                    }
                    is ResolveResult.Ok -> {
                        for (address in result.addresses) known[address] = now
                    }
                    is ResolveResult.Periodic -> {}
                }
                known.values.removeAll { mark -> mark.elapsedNow() > keepAlive }
                val current = known.keys.sorted().toSet()
                if (current != lastResult) {
                    val added = current.minus(lastResult)
                    val removed = lastResult.minus(current)
                    buildString {
                        append(host)
                        append(":")
                        if (added.isNotEmpty()) {
                            append(" (+) ")
                            appendListForLog(added)
                        }
                        if (removed.isNotEmpty()) {
                            append(" (-) ")
                            appendListForLog(removed)
                        }
                        append(" = ")
                        append(current.size)
                        append(" IPs")
                    }.let { log(it) }
                    emit(current)
                    lastResult = current
                }
            }
        }
    }
}
