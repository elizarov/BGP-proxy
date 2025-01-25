import kotlinx.coroutines.CoroutineDispatcher

expect fun createSelectorDispatcher(): CoroutineDispatcher
expect fun currentTimestamp(): String
