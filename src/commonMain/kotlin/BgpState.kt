@file:OptIn(ExperimentalUnsignedTypes::class)

import io.ktor.utils.io.core.*

data class BgpUpdate(
    val withdrawn: Set<IpAddressPrefix> = emptySet(),
    val reachable: Set<IpAddressPrefix> = emptySet(),
    val attributes: BgpAttributes = BgpAttributes()
) {
    override fun toString(): String =
        "withdrawn=${withdrawn.size}, reachable=${reachable.size}; attr=${attributes.bytes.size} bytes"
}

data class BgpState(
    val prefixes: Set<IpAddressPrefix> = emptySet(),
    val attributes: BgpAttributes = BgpAttributes()
) {
    fun apply(update: BgpUpdate): BgpState {
        val newPrefixes = prefixes.toMutableSet()
        newPrefixes -= update.withdrawn
        newPrefixes += update.reachable
        return BgpState(newPrefixes, update.attributes.takeIf { !it.isEmpty() } ?: attributes)
    }

    fun diffFrom(lastState: BgpState): BgpUpdate =
        BgpUpdate(
            withdrawn = lastState.prefixes - prefixes,
            reachable = prefixes - lastState.prefixes,
            attributes
        )

    override fun toString(): String = "${prefixes.size} prefixes"
}

class BgpAttributes(val bytes: ByteArray = byteArrayOf()) {
    fun isEmpty() = bytes.isEmpty()
    override fun equals(other: Any?): Boolean = other is BgpAttributes && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = bytes.toHexString()
}

fun ByteReadPacket.readPrefixes(totalLength: Int): Set<IpAddressPrefix> = buildSet {
    var remaining = totalLength
    while (remaining > 0) {
        val length = readUByte().toInt()
        check(length in 0..32) { "BGP: Prefix length must be in range 0..32 but got: $length" }
        val n = prefixBytes(length)
        val prefix = readBytes(n)
        add(IpAddressPrefix(length, prefix))
        remaining -= 1 + n
    }
}

fun composePrefixes(set: MutableSet<IpAddressPrefix>, maxLength: Int) = buildPacket {
    var remBytes = maxLength
    val composed = mutableSetOf<IpAddressPrefix>()
    for (prefix in set) {
        if (remBytes <= 0) break
        val length = prefix.length
        writeByte(length.toByte())
        for (i in 0 until prefix.nBytes) writeByte(prefix[i])
        composed += prefix
        remBytes -= 1 + prefixBytes(length)
    }
    set -= composed
}

sealed interface AddressRange

data class HostName(val host: String) : AddressRange

class IpAddressPrefix(
    val length: Int = 32,
    val bits: Int
) : AddressRange {
    init {
        val mask = (1 shl (32 - length)) - 1
        check(bits and mask == 0)
    }
    val nBytes: Int get() = prefixBytes(length)
    operator fun get(i: Int): Byte = (bits shr ((3 - i) * 8)).toByte()
    fun bitAt(i: Int) = (bits shr (31 - i)) and 1
    override fun toString(): String {
        val sb = StringBuilder()
        for (i in 0 until nBytes) {
            if (i != 0) sb.append('.')
            sb.append(this[i].toUByte())
        }
        if (length < 32) sb.append("/$length")
        return sb.toString()
    }
    override fun equals(other: Any?): Boolean =
        other is IpAddressPrefix && other.length == length && other.bits == bits
    override fun hashCode(): Int = length * 31 + bits
}

fun IpAddressPrefix(length: Int = 32, prefix: ByteArray): IpAddressPrefix {
    val n = prefix.size
    check(n == prefixBytes(length))
    var bits = 0
    for (i in 0 until n) {
        bits = bits or ((prefix[i].toInt() and 0xff) shl ((3 - i) * 8))
    }
    return IpAddressPrefix(length, bits)
}

fun prefixBytes(length: Int) = (length + 7) / 8

fun ByteArray.toHexString(): String =
    joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
