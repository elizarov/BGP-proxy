import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlin.time.Duration

suspend fun retryIndefinitely(log: Log, delay: Duration, action: suspend () -> Unit) {
    repeatEvery(delay) {
        catchAndLogErrors(log) {
            action()
        }
    }
}

inline fun catchAndLogErrors(log: Log, action: () -> Unit) {
    try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log("Error: ${e.message}")
        if (e !is ClosedReceiveChannelException && e !is IllegalStateException && !isIoException(e)) {
            e.printStackTrace()
        }
    }
}

suspend fun repeatEvery(delay: Duration, action: suspend () -> Unit) {
    while (true) {
        action()
        delay(delay)
    }
}
