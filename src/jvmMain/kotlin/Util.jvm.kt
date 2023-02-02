@file:OptIn(DelicateCoroutinesApi::class)

import kotlinx.coroutines.*
import java.io.File
import java.text.*
import java.util.*

actual fun createSelectorDispatcher(): CoroutineDispatcher =
    Dispatchers.IO

actual fun createResolverDispatcher(): CoroutineDispatcher =
    newSingleThreadContext("Resolver")

actual fun currentTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date())

actual fun readFileBytesCatching(file: String): Result<ByteArray> =
    runCatching { File(file).readBytes() }