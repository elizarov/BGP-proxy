import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

class Log(private val id: String) {
    operator fun invoke(msg: String) {
        println("${currentTimestamp()} [$id]: $msg")
    }
}

fun StringBuilder.appendListForLog(list: Collection<*>) {
    val maxN = 2
    append(list.take(maxN).joinToString(", "))
    if (list.size > maxN) {
        append(", ")
        append(list.size - maxN)
        append(" more")
    }
}

suspend fun <T> retryOperation(log: Log, description: String, interval: Duration = 5.seconds, operation: suspend () -> T, ): T {
    while (true) {
        try {
            return operation().also {
                log(description)
            }
        } catch (e: Exception) {
            log("Error: $description: $e")
        }
        delay(interval)
    }
}

fun SocketAddress.toLogString(): String =
    (this as? InetSocketAddress)?.let { "${it.hostname}:${it.port}" } ?: toString()

