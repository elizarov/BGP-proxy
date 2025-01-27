@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlin.time.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.*

private val resolveAgainOnError = 5.seconds
private val nativeResolveTtl = 1.seconds
private val keepAlive = 1.hours
private val stopAfter = 10.seconds

sealed class ResolveResult {
    data class Ok(val list: List<IpAddress>, val ttl: Duration = nativeResolveTtl) : ResolveResult()
    data class Err(val message: String) : ResolveResult()
}

fun interface Resolver {
    suspend fun resolve(host: String): ResolveResult
}

fun interface ResolverFactory {
    fun getResolver(host: String): Resolver
}

class HostResolver(
    private val coroutineScope: CoroutineScope,
    private val resolverFactory: ResolverFactory
) {
    private val mutex = Mutex()
    // mutations are protected with mutex
    private val flows = HashMap<String, Flow<List<IpAddress>>>()
    private val log = Log("resolve")

    suspend fun resolveFlow(host: String): Flow<List<IpAddress>> = mutex.withLock {
        flows.getOrPut(host) {
            newResolveFlow(host).stateIn(coroutineScope, SharingStarted.WhileSubscribed(stopAfter), emptyList())
        }
    }

    private fun newResolveFlow(host: String): Flow<List<IpAddress>> {
        val resolver = resolverFactory.getResolver(host)
        return flow {
            val known = LinkedHashMap<IpAddress, Monotonic.ValueTimeMark>()
            var lastResult = emptyList<IpAddress>()
            var lastError: String? = null
            while (true) {
                val result = resolver.resolve(host)
                val now = Monotonic.markNow()
                val resolveAgain: Duration = when (result) {
                    is ResolveResult.Err -> {
                        if (result.message != lastError) {
                            lastError = result.message
                            log("$host: $lastError")
                        }
                        resolveAgainOnError
                    }
                    is ResolveResult.Ok -> {
                        for (address in result.list) known[address] = now
                        result.ttl
                    }
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
                delay(resolveAgain)
            }
        }
    }
}
