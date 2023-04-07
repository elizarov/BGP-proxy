@file:OptIn(ExperimentalCoroutinesApi::class)

import io.ktor.utils.io.errors.*
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.*

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

actual fun readFileBytesCatching(file: String): Result<ByteArray> {
    val fd = fopen(file, "r") ?: return Result.failure(IOException("File not found"))
    val chunks = ArrayList<ByteArray>()
    memScoped {
        val chunkSize = 4096uL
        val buf = malloc(chunkSize) ?: return Result.failure(IOException("malloc failure"))
        do {
            val n = fread(buf, 1, chunkSize, fd)
            if (n != 0uL) chunks += buf.readBytes(n.toInt())
        } while (n == chunkSize)
    }
    val err = ferror(fd) != 0
    fclose(fd)
    if (err) return Result.failure(IOException("File read error"))
    val sumSize = chunks.sumOf { it.size }
    val res = ByteArray(sumSize)
    var i = 0
    for (chunk in chunks) {
        chunk.copyInto(res, i)
        i += chunk.size
    }
    return Result.success(res)
}