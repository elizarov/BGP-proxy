import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

expect fun nativeResolveHostAddr(host: String): ResolveResult

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun newNativeResolver(): Resolver {
    val nativeResolveDispatcher = newSingleThreadContext("Resolver")
    return Resolver { host ->
        withContext(nativeResolveDispatcher) {
            nativeResolveHostAddr(host)
        }
    }
}
