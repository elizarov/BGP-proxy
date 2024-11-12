import BgpAttrFlag.Optional
import BgpAttrFlag.Transitive
import BgpAttrType.*
import io.ktor.utils.io.core.*
import kotlinx.io.*

typealias BgpAttributes = List<BgpAttribute>
typealias BgpCommunities = Set<BgpCommunity>

val emptyBgpCommunities: BgpCommunities = emptySet()

/* Example attribute bytes
   40 01 01 00 -- ORIGIN = IGP
   40 02 04 0201ff98 -- AS_PATH (AS_SEQUENCE, 1 AS #ff98 = 65432)
   40 03 04 2d9a4947 -- NEXT_HOP (2d9a4947 = 45.154.73.17)
   c0 08 04 ff9800c8 -- COMMUNITY c8 in AS ff98
*/

val BgpAttributes.communities: BgpCommunities get() =
    find { it.type == COMMUNITY.type }?.let { BgpCommunities(it.bytes ) } ?: emptyBgpCommunities

private val originIgpBytes = byteArrayOf(0)

fun createAttributes(endpoint: BgpEndpoint, communities: BgpCommunities): BgpAttributes = buildList {
    add(BgpAttribute(ORIGIN.type, originIgpBytes))
    add(BgpAttribute(AS_PATH.type, buildPacket {
      writeByte(2)
      writeByte(1)
      writeUShort(endpoint.autonomousSystem)
    }.readByteArray()))
    add(BgpAttribute(NEXT_HOP.type, endpoint.address.bytes))
    if (communities.isNotEmpty()) {
        add(BgpAttribute(COMMUNITY.type, communities.toBytes()))
    }
}

fun Source.readAttributes(totalLength: Int): BgpAttributes = buildList {
    var remaining = totalLength
    while (remaining > 0) {
        val flags = readUByte()
        val type = readUByte()
        remaining -= 2
        val n = if (BgpAttrFlag.ExtendedLength in flags) {
            remaining -= 2
            readUShort().toInt()
        } else {
            remaining--
            readUByte().toInt()
        }
        remaining -= n
        val data = readByteArray(n)
        add(BgpAttribute(type, data, flags))
    }
}

fun composeAttributes(attributes: BgpAttributes): Source = buildPacket {
    for (attr in attributes) {
        writeUByte(attr.flags)
        writeUByte(attr.type)
        if (BgpAttrFlag.ExtendedLength in attr.flags) {
            writeUShort(attr.bytes.size.toUShort())
        } else {
            writeUByte(attr.bytes.size.toUByte())
        }
        writeFully(attr.bytes)
    }
}

enum class BgpAttrFlag(val char: Char, val mask: UByte) {
    Optional('O',0x80u),
    Transitive('T', 0x40u),
    Partial('P', 0x20u),
    ExtendedLength('E',0x10u)
}

enum class BgpAttrType(val type: UByte, val defaultFlags: UByte) {
    ORIGIN(1u, Transitive.mask),
    AS_PATH(2u, Transitive.mask),
    NEXT_HOP(3u, Transitive.mask),
    COMMUNITY(8u, Transitive.mask or Optional.mask)
}

data class BgpCommunity(
    val autonomousSystem: UShort,
    val community: UShort
) : Comparable<BgpCommunity> {
    override fun toString(): String = "$autonomousSystem:$community"
    override fun compareTo(other: BgpCommunity): Int =
        compareValuesBy(this, other, BgpCommunity::autonomousSystem, BgpCommunity::community)
}

fun BgpCommunities(bytes: ByteArray): BgpCommunities =
    with(ByteReadPacket(bytes)) {
        buildSet {
            while (remaining >= 4) add(BgpCommunity(readUShort(), readUShort()))
        }
    }

fun BgpCommunities.toBytes(): ByteArray = buildPacket {
    for (c in this@toBytes) {
        writeUShort(c.autonomousSystem)
        writeUShort(c.community)
    }
}.readByteArray()

operator fun UByte.contains(flag: BgpAttrFlag) = (this and flag.mask) != 0.toUByte()

class BgpAttribute(
    val type: UByte,
    val bytes: ByteArray,
    val flags: UByte = BgpAttrType.entries.find { it.type == type }!!.defaultFlags.let {
        f -> if (bytes.size > 0xff) f or BgpAttrFlag.ExtendedLength.mask else f
    }
) {
    override fun equals(other: Any?): Boolean =
        other is BgpAttribute && flags == other.flags && type == other.type && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = when {
        type == ORIGIN.type && flags == ORIGIN.defaultFlags && bytes.contentEquals(originIgpBytes) ->
            "IGP"
        type == AS_PATH.type && flags == AS_PATH.defaultFlags && bytes.size == 4 && bytes[0] == 2.toByte() && bytes[1] == 1.toByte() ->
            "AS${(bytes[2].toUByte().toInt() shl 8) + bytes[3].toUByte().toInt()}"
        type == NEXT_HOP.type && flags == NEXT_HOP.defaultFlags && bytes.size == 4 ->
            IpAddress(bytes).toString()
        type == COMMUNITY.type && flags == COMMUNITY.defaultFlags && bytes.isNotEmpty() && bytes.size % 4 == 0 ->
            BgpCommunities(bytes).toString()
        else -> buildString {
            BgpAttrType.entries.find { it.type == type }?.let { append(it.name) } ?: append(type)
            append('{')
            for (flag in BgpAttrFlag.entries) if (flag in flags) append(flag.char)
            append("}")
            append(bytes.toHexString())
        }
    }
}