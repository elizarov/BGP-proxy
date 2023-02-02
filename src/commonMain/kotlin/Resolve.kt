import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource.*

sealed class ResolveResult {
    data class Ok(val list: List<IpAddressPrefix>) : ResolveResult()
    data class Err(val message: String) : ResolveResult()
}

expect fun resolveHostAddr(host: String): ResolveResult

private val resolveScope = CoroutineScope(SupervisorJob() + createResolverDispatcher())
private val resolveFlows = HashMap<String, Flow<List<IpAddressPrefix>>>()
private val resolveLog = Log("resolve")

private val RESOLVE_AGAIN = 1.seconds
private val KEEP_ALIVE = 1.hours

fun resolveFlow(host: String): Flow<List<IpAddressPrefix>> =
    resolveFlows.getOrPut(host) {
        newResolveFlow(host).stateIn(resolveScope, SharingStarted.WhileSubscribed(KEEP_ALIVE), emptyList())
    }

private fun newResolveFlow(host: String) = flow {
    val known = LinkedHashMap<IpAddressPrefix, Monotonic.ValueTimeMark>()
    var last = emptyList<IpAddressPrefix>()
    while (true) {
        val result = resolveHostAddr(host)
        val now = Monotonic.markNow()
        when (result) {
            is ResolveResult.Err -> resolveLog("$host: ${result.message}")
            is ResolveResult.Ok -> for (address in result.list) known[address] = now
        }
        known.values.removeAll { mark -> mark.elapsedNow() > KEEP_ALIVE }
        val current = known.keys.toList()
        if (current != last) {
            resolveLog("$host -> ${current.joinToString(", ")}")
            emit(current)
            last = current
        }
        delay(RESOLVE_AGAIN)
    }
}