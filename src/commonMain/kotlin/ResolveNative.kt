import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

expect fun nativeResolveHostAddr(host: String): ResolveResult

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun newNativeResolverFactory(): ResolverFactory {
    val nativeResolveDispatcher = Dispatchers.IO.limitedParallelism(1)
    return ResolverFactory { host ->
        flow {
            val result = nativeResolveHostAddr(host)
            emit(result)
            delay(result.ttl)
        }.flowOn(nativeResolveDispatcher)
    }
}
