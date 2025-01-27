@file:OptIn(ExperimentalStdlibApi::class)

import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.toByteArray
import kotlinx.io.*

const val DNS_PORT = 53

enum class DnsFlag(val shift: Int, val bits: Int = 1) {
    /**
     * A one bit field that specifies whether this message is a query (0), or a response (1).
     */
    QR(15),
    /**
     * Authoritative Answer - this bit is valid in responses and specifies that the responding name server is an
     * authority for the domain name in question section.
     */
    AA(10),
    /**
     * TrunCation - specifies that this message was truncate due to length greater than that permitted on the
     * transmission channel.
     */
    TC(9),
    /**
     * Recursion Desired - this bit may be set in a query and is copied into the response. If RD is set, it directs
     * the name server to pursue the query recursively. Recursive query support is optional.
     */
    RD(8),
    /**
     * Recursion Available - this be is set or cleared in a response, and denotes whether recursive query support is
     * available in the name server.
     */
    RA(7),
    /**
     * A four bit field that specifies kind of query in this message.  This value is set by the originator of a query
     * and copied into the response. The value is 0 for standard query (QUERY).
     */
    OPCODE(11, 4),
    /**
     * Response code - this 4 bit field is set as part of responses. See [DnsRCode].
     */
    RCODE(0, 4);

    fun get(flags: UShort): Int = (flags.toInt() shr shift) and ((1 shl bits) - 1)
    fun value(x: Int) = x shl shift
}

enum class DnsRCode(val code: Int) {
    NoError(0),
    FormatError(1),
    ServerFailure(2),
    NameError(3),
    NotImplemented(4),
    Refused(5)
}

fun Int.toDnsRCodeString(): String {
    val code = this
    val rc = DnsRCode.entries.find { it.code == code }
    if (rc != null) return rc.name
    return "RC$code"
}

fun DnsRCode.toResponseFlags(): UShort =
    (DnsFlag.QR.value(1) or DnsFlag.RCODE.value(code)).toUShort()

data class DnsMessage(
    val id: UShort,
    val flags: UShort,
    val question: DnsQuestion? = null,
    val answer: List<DnsAnswer> = emptyList(),
    val authority: List<DnsAnswer> = emptyList(),
    val additional: List<DnsAnswer> = emptyList()
) {
    val isQuery: Boolean get() = DnsFlag.QR.get(flags) == 0
    val opCode: Int get() = DnsFlag.OPCODE.get(flags)
    val rCode: Int get() = DnsFlag.RCODE.get(flags)

    @OptIn(ExperimentalStdlibApi::class)
    override fun toString(): String = buildString {
        append('#')
        append(id.toHexString())
        for (flag in DnsFlag.entries) {
            val value = flag.get(flags)
            if (value != 0) {
                append(' ')
                when {
                    flag.bits == 1 -> append(flag.name)
                    flag == DnsFlag.RCODE -> append(value.toDnsRCodeString())
                    else -> {
                        append(flag.name)
                        append(value)
                    }
                }
            }
        }
        question?.let {
            append(' ')
            append(it)
        }
        answer.forEach {
            append("\n\tAN ")
            append(it)
        }
        authority.forEach {
            append("\n\tNS ")
            append(it)
        }
        additional.forEach {
            append("\n\tAR ")
            append(it)
        }
    }
}

fun String.toDnsName(): DnsName? {
    if (isEmpty()) return null
    val i = indexOf('.')
    if (i < 0) return DnsName(toByteArray())
    return DnsName(substring(0, i).toByteArray(), substring(i + 1).toDnsName())
}

class DnsName(val label: ByteArray, val next: DnsName? = null) : DnsData {
    private var hash = 0

    override fun equals(other: Any?): Boolean =
        this === other || other is DnsName && label.contentEquals(other.label) && next == other.next

    override fun hashCode(): Int {
        if (hash == 0) hash = label.contentHashCode() * 7919 + next.hashCode()
        return hash
    }

    override fun toString(): String = buildString {
        var cur = this@DnsName
        while (true) {
            append(cur.label.decodeToString())
            if (cur.next == null) break
            append('.')
            cur = cur.next
        }
    }

    companion object {
        const val POINTER_FLAG = 0xc0
        const val POINTER_MASK = 0x3f
        const val POINTER_MAX = (POINTER_MASK shl 8) or 0xff
        val EMPTY = DnsName(ByteArray(0))
    }
}

sealed interface DnsData

class DnsBytes(val bytes: ByteArray) : DnsData {
    override fun toString(): String = bytes.toHexString()
}

sealed class DnsDataType<T : DnsData> {
    abstract fun readFrom(packet: DnsPacket, size: Int): T
    abstract fun writeImpl(packet: DnsPacketBuilder, data: T)
    @Suppress("UNCHECKED_CAST")
    fun write(packet: DnsPacketBuilder, data: DnsData) { writeImpl(packet, data as T) }

    object Bytes : DnsDataType<DnsBytes>() {
        override fun readFrom(packet: DnsPacket, size: Int): DnsBytes = DnsBytes(packet.readByteArray(size))
        override fun writeImpl(packet: DnsPacketBuilder, data: DnsBytes) = packet.write(data.bytes)
    }
    object Name : DnsDataType<DnsName>() {
        override fun readFrom(packet: DnsPacket, size: Int): DnsName = packet.readDnsName() ?: DnsName.EMPTY
        override fun writeImpl(packet: DnsPacketBuilder, data: DnsName) { packet.writeDnsName(data) }
    }
    object Address : DnsDataType<IpAddress>() {
        override fun readFrom(packet: DnsPacket, size: Int): IpAddress = IpAddress(packet.readByteArray(4))
        override fun writeImpl(packet: DnsPacketBuilder, data: IpAddress) { packet.write(data.bytes) }
    }
    object SOA : DnsDataType<DnsSoaData>() {
        override fun readFrom(packet: DnsPacket, size: Int): DnsSoaData = packet.readSoaData()
        override fun writeImpl(packet: DnsPacketBuilder, data: DnsSoaData) { packet.writeSoaData(data) }
    }
}

// basic from rfc1035
enum class DnsType(val code: UShort, val type: DnsDataType<*>) {
    A(1u, DnsDataType.Address),
    NS(2u, DnsDataType.Name),
    CNAME(5u, DnsDataType.Name),
    SOA(6u, DnsDataType.SOA),
    PTR(12u, DnsDataType.Name),
    TXT(16u, DnsDataType.Bytes),
    AAAA(28u, DnsDataType.Bytes), // rfc3596
    SRV(33u, DnsDataType.Bytes), // rfc2782
    SVCB(64u, DnsDataType.Bytes), // rfc9460
    HTTPS(65u, DnsDataType.Bytes) // rfc9460
}

private val codeToDnsType: Map<UShort, DnsType> = DnsType.entries.associate { it.code to it }

fun UShort.isDnsTypeSupported(): Boolean = codeToDnsType.containsKey(this)

fun UShort.toDnsDataType(): DnsDataType<*> = codeToDnsType[this]?.type ?: DnsDataType.Bytes

fun UShort.toDnsTypeString(): String {
    val code = this
    val type = codeToDnsType[code]
    if (type != null) return type.name
    return "T$code"
}

enum class DnsClass(val code: UShort) { IN(1u) }

fun UShort.toDnsClassString(): String {
    val code = this
    val cls = DnsClass.entries.find { it.code == code }
    if (cls != null) return cls.name
    return "C$code"
}

class DnsQuestion(
    val qName: DnsName,
    val qType: UShort,
    val qClass: UShort
) {
    override fun toString(): String = buildString {
        append(qName)
        append(' ')
        append(qType.toDnsTypeString())
        append(' ')
        append(qClass.toDnsClassString())
    }
}

class DnsAnswer(
    val name: DnsName,
    val aType: UShort,
    val aClass: UShort,
    val ttl: UInt,
    val rData: DnsData // depends on aType
) {
    override fun toString(): String = buildString {
        append(name)
        append(' ')
        append(aType.toDnsTypeString())
        append(' ')
        append(aClass.toDnsClassString())
        append(" TTL:")
        append(ttl)
        append(' ')
        append(rData)
    }
}

class DnsSoaData(
    val mName: DnsName,
    val rName: DnsName,
    val serial: UInt,
    val refresh: UInt,
    val retry: UInt,
    val expire: UInt,
    val minimum: UInt
): DnsData {
    override fun toString(): String = buildString {
        append(mName)
        append(' ')
        append(rName)
        append(" SN#")
        append(serial.toHexString())
        append(" Refresh:")
        append(refresh)
        append(" Retry:")
        append(retry)
        append(" Expire:")
        append(expire)
        append(" Minimum:")
        append(minimum)
    }
}

// ------------------- reading/writing -------------------

fun Source.readDnsMessage(): DnsMessage =
    readByteArray().readDnsMessage()

fun ByteArray.readDnsMessage(): DnsMessage {
    val packet = DnsPacket(this)
    try {
        return packet.readDnsMessage()
    } catch (e: DnsProtocolFormatException) {
        throw DnsProtocolFormatException("${e.message} in ${this.toHexString()}")
    }
}

fun DnsPacket.readDnsMessage(): DnsMessage {
    val id = readUShort()
    val flags = readUShort()
    val qdCount = readUShort()
    val anCount = readUShort()
    val nsCount = readUShort()
    val arCount = readUShort()
    if (qdCount > 1u) throw DnsProtocolFormatException("QDCOUNT > 1")
    val question = if (qdCount > 0u) readDnsQuestion() else null
    val answer = List(anCount.toInt()) { readDnsAnswer() }
    val authority = List(nsCount.toInt()) { readDnsAnswer() }
    val additional = List(arCount.toInt()) { readDnsAnswer() }
    return DnsMessage(id, flags, question, answer, authority, additional)
}

fun DnsPacketBuilder.writeDnsMessage(message: DnsMessage) {
    writeUShort(message.id)
    writeUShort(message.flags)
    writeUShort(if (message.question == null) 0u else 1u)
    writeUShort(message.answer.size.toUShort())
    writeUShort(message.authority.size.toUShort())
    writeUShort(message.additional.size.toUShort())
    message.question?.let { writeDnsQuestion(it) }
    message.answer.forEach { writeDnsAnswer(it) }
    message.authority.forEach { writeDnsAnswer(it) }
    message.additional.forEach { writeDnsAnswer(it) }
}

fun DnsPacket.readDnsName(): DnsName? {
    val offset = offset
    val len = readUByte().toInt()
    if (len == 0) return null
    if ((len and DnsName.POINTER_FLAG) == DnsName.POINTER_FLAG) {
        val second = readUByte().toInt()
        val ptr = ((len and DnsName.POINTER_MASK) shl 8) or second
        if (ptr >= offset) throw DnsProtocolFormatException("Future pointer to 0x${ptr.toUShort().toHexString()} at offset 0x${offset.toUShort().toHexString()}")
        val name = getNameAt(ptr)
        return name ?: readDnsNameAt(ptr)
    }
    if (len > DnsName.POINTER_MASK) {
        throw DnsProtocolFormatException("Invalid label length ${len.toUByte().toHexString()} at offset 0x${offset.toUShort().toHexString()}")
    }
    val name = DnsName(readByteArray(len), readDnsName())
    putNameAt(name, offset)
    return name
}

fun DnsPacket.readDnsNameAt(ptr: Int): DnsName? {
    val curOffset = offset
    offset = ptr
    return readDnsName().also { offset = curOffset }
}

fun DnsPacketBuilder.writeDnsName(name: DnsName) {
    var cur: DnsName = name
    while (true) {
        val ptr = getNameOffset(cur)
        if (ptr != 0) {
            writeUByte(((ptr shr 8) or DnsName.POINTER_FLAG).toUByte())
            writeUByte(ptr.toUByte())
            return
        } else {
            putNameAt(cur, offset)
            val size = cur.label.size
            writeUByte(size.toUByte())
            if (size == 0) return // stop at DnsName.EMPTY label
            write(cur.label)
        }
        if (cur.next == null) break
        cur = cur.next
    }
    writeUByte(0u)
}

fun DnsPacket.readDnsQuestion(): DnsQuestion {
    val qName = readDnsName() ?: DnsName.EMPTY
    val qType = readUShort()
    val qClass = readUShort()
    return DnsQuestion(qName, qType, qClass)
}

fun DnsPacketBuilder.writeDnsQuestion(question: DnsQuestion) {
    writeDnsName(question.qName)
    writeUShort(question.qType)
    writeUShort(question.qClass)
}

fun DnsPacket.readDnsAnswer(): DnsAnswer {
    val name = readDnsName() ?: DnsName.EMPTY
    val aType = readUShort()
    val aClass = readUShort()
    val ttl = readUInt()
    val rdLen = readUShort().toInt()
    val type = aType.toDnsDataType()
    val dataStart = offset
    val rData = type.readFrom(this, rdLen)
    if (offset != dataStart + rdLen) throw DnsProtocolFormatException("Invalid data size for type $aType at offset 0x${dataStart.toUShort().toHexString()}")
    return DnsAnswer(name, aType, aClass, ttl, rData)
}

fun DnsPacketBuilder.writeDnsAnswer(answer: DnsAnswer) {
    writeDnsName(answer.name)
    writeUShort(answer.aType)
    writeUShort(answer.aClass)
    writeUInt(answer.ttl)
    val type = answer.aType.toDnsDataType()
    val start = offset + 2
    var size: Int
    val dataPacket = buildPacket {
        val builder = DnsPacketBuilder(this, start, names)
        type.write(builder, answer.rData)
        size = builder.offset - start
    }
    writeUShort(size.toUShort())
    write(dataPacket.readByteArray(size))
}

fun DnsPacket.readSoaData(): DnsSoaData {
    val mName = readDnsName() ?: DnsName.EMPTY
    val rName = readDnsName() ?: DnsName.EMPTY
    val serial = readUInt()
    val refresh = readUInt()
    val retry = readUInt()
    val expire = readUInt()
    val minimum = readUInt()
    return DnsSoaData(mName, rName, serial, refresh, retry, expire, minimum)
}

fun DnsPacketBuilder.writeSoaData(data: DnsSoaData) {
    writeDnsName(data.mName)
    writeDnsName(data.rName)
    writeUInt(data.serial)
    writeUInt(data.refresh)
    writeUInt(data.retry)
    writeUInt(data.expire)
    writeUInt(data.minimum)
}

// ------------------- util -------------------

class DnsProtocolFormatException(message: String) : IOException(message)

class DnsPacket(val bytes: ByteArray) {
    var offset = 0
    private val names = HashMap<Int, DnsName>()

    private fun expect(n: Int) {
        if (offset + n > bytes.size) throw DnsProtocolFormatException("packet ends unexpectedly at offset $offset, expected $n bytes")
    }

    private fun byte(i: Int): UByte = bytes[offset + i].toUByte()

    fun readUByte(): UByte {
        expect(1)
        return byte(0).also { offset++ }
    }

    fun readUShort(): UShort {
        expect(2)
        return ((byte(0).toInt() shl 8) or byte(1).toInt()).toUShort().also { offset += 2 }
    }

    fun readUInt(): UInt {
        expect(4)
        return ((byte(0).toInt() shl 24) or
                (byte(1).toInt() shl 16) or
                (byte(2).toInt() shl 8) or
                byte(3).toInt()).toUInt().also { offset += 4 }
    }

    fun readByteArray(size: Int): ByteArray {
        expect(size)
        return bytes.copyOfRange(offset, offset + size).also { offset += size }
    }

    fun putNameAt(name: DnsName, i: Int) {
        names[i] = name
    }

    fun getNameAt(i: Int): DnsName? = names[i]
}

fun DnsMessage.buildMessagePacket(): Source =
    buildPacket { DnsPacketBuilder(this).writeDnsMessage(this@buildMessagePacket) }

class DnsPacketBuilder(
    val sink: Sink,
    var offset: Int = 0,
    val names: HashMap<DnsName, Int> = HashMap()
) {
    fun writeUByte(x: UByte) {
        sink.writeUByte(x)
        offset++
    }

    fun writeUShort(x: UShort) {
        sink.writeUShort(x)
        offset += 2
    }

    fun writeUInt(x: UInt) {
        sink.writeUInt(x)
        offset += 4
    }

    fun write(a: ByteArray) {
        sink.write(a)
        offset += a.size
    }

    fun putNameAt(name: DnsName, i: Int) {
        if (i <= DnsName.POINTER_MAX) names[name] = i
    }

    fun getNameOffset(name: DnsName): Int = names[name] ?: 0
}