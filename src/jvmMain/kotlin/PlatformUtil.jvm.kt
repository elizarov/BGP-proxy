import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

actual fun createSelectorDispatcher(): CoroutineDispatcher =
    Dispatchers.IO

actual fun currentTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date())

actual fun readFileBytesCatching(file: String): Result<ByteArray> =
    runCatching { File(file).readBytes() }