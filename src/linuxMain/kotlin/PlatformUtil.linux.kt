@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.*

@OptIn(DelicateCoroutinesApi::class)
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