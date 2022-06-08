import kotlinx.coroutines.*

expect fun createSelectorDispatcher(): CoroutineDispatcher
expect fun currentTimestamp(): String