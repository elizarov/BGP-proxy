import kotlinx.coroutines.CoroutineDispatcher

expect fun createSelectorDispatcher(): CoroutineDispatcher
expect fun currentTimestamp(): String

class Log(private val id: String) {
    operator fun invoke(msg: String) {
        println("${currentTimestamp()} [$id]: $msg")
    }
}