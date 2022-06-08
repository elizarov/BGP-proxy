import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*

@OptIn(ExperimentalCoroutinesApi::class)
actual fun createSelectorDispatcher(): CoroutineDispatcher =
    newSingleThreadContext("Selector")

actual fun currentTimestamp(): String = memScoped {
    val time = alloc<time_tVar>()
    time(time.ptr)
    val tm = alloc<tm>()
    localtime_r(time.ptr, tm.ptr)
    with(tm) {
        "${(tm_year + 1900).fmt(4)}-${(tm_mon + 1).fmt(2)}-${tm_mday.fmt(2)} " +
            "${tm_hour.fmt(2)}:${tm_min.fmt(2)}:${tm_sec.fmt(2)}"
    }
}

private fun Int.fmt(digits: Int) = toString().padStart(digits, '0')