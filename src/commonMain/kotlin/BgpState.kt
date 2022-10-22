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
        writeFully(prefix.prefix)
        composed += prefix
        remBytes -= 1 + prefixBytes(length)
    }
    set -= composed
}

class IpAddressPrefix(
    val length: Int,
    val prefix: ByteArray
) {
    init {
        check(prefix.size == prefixBytes(length))
    }
    fun bitAt(i: Int) = prefix.bitAt(i)
    override fun toString(): String = "${prefix.copyOf(4).toHexString()}/$length"
    override fun equals(other: Any?): Boolean =
        other is IpAddressPrefix && other.length == length && other.prefix.contentEquals(prefix)
    override fun hashCode(): Int = length * 31 + prefix.contentHashCode()
}

fun prefixBytes(length: Int) = (length + 7) / 8

fun ByteArray.toHexString(): String =
    joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
