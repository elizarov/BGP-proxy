import kotlinx.coroutines.*

expect fun createSelectorDispatcher(): CoroutineDispatcher
expect fun currentTimestamp(): String
expect fun readFileBytesCatching(file: String): Result<ByteArray>