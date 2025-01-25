
class Log(private val id: String) {
    operator fun invoke(msg: String) {
        println("${currentTimestamp()} [$id]: $msg")
    }
}

fun StringBuilder.appendListForLog(list: List<*>) {
    val maxN = 2
    append(list.take(maxN).joinToString(", "))
    if (list.size > maxN) {
        append(", ")
        append(list.size - maxN)
        append(" more")
    }
}