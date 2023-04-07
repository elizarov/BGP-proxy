
class IpAddress(val bytes: ByteArray) {
    constructor(s: String) : this(
        s.split(".").also {
            check(it.size == 4) { "Address must be 'xxx.xxx.xxx.xxx' but found: $s" }
        }.map { it.toUByte().toByte() }.toByteArray()
    )

    override fun toString(): String = bytes.joinToString(".") { it.toUByte().toString() }
}