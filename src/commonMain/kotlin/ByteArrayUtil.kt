
fun ByteArray.toHexString(): String =
    joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

fun ByteArray.bitAt(i: Int) =
    (get(i / 8).toInt() shr (7 - i % 8)) and 1
