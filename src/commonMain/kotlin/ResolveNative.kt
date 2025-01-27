import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

expect fun nativeResolveHostAddr(host: String): ResolveResult

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun newNativeResolverFactory(): ResolverFactory {
    val nativeResolveDispatcher = newSingleThreadContext("Resolver")
    return ResolverFactory { host ->
        flow {
            val result = nativeResolveHostAddr(host)
            emit(result)
            delay(result.ttl)
        }.flowOn(nativeResolveDispatcher)
    }
}
