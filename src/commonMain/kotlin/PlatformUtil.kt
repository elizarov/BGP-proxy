import kotlinx.coroutines.CoroutineDispatcher

expect fun createSelectorDispatcher(): CoroutineDispatcher
expect fun currentTimestamp(): String
expect fun readFileBytesCatching(file: String): Result<ByteArray>
expect fun isIoException(e: Throwable): Boolean

class Log(private val id: String) {
    operator fun invoke(msg: String) {
        println("${currentTimestamp()} [$id]: $msg")
    }
}