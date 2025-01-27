import kotlinx.io.Sink

class IpAddress(val bits: Int) : DnsData, Comparable<IpAddress> {
    val bytes: ByteArray get() = ByteArray(4) { i -> (bits shr ((3 - i) * 8)).toByte() }

    constructor(s: String) : this(
        s.split(".").also {
            check(it.size == 4) { "Address must be 'xxx.xxx.xxx.xxx' but found: $s" }
        }.map { it.toUByte().toByte() }.toByteArray().toAddressBits()
    )

    override fun toString(): String = bytes.joinToString(".") { it.toUByte().toString() }
    override fun equals(other: Any?): Boolean = this === other || other is IpAddress && bits == other.bits
    override fun hashCode(): Int = bits.hashCode()
    override fun compareTo(other: IpAddress): Int = bits.toUInt().compareTo(other.bits.toUInt())
}

fun IpAddress.toIpAddressPrefix(): IpAddressPrefix = IpAddressPrefix(bits = bits)

fun ByteArray.toIpAddress(): IpAddress = IpAddress(toAddressBits())

fun Sink.write(address: IpAddress) {
    writeInt(address.bits)
}

private fun ByteArray.toAddressBits(): Int {
    require(size == 4)
    var address = 0
    for (i in 0 until 3) {
        address = address or ((get(i).toInt() and 0xff) shl ((3 - i) * 8))
    }
    return address
}