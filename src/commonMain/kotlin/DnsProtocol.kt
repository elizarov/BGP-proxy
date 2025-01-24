import io.ktor.utils.io.core.buildPacket
import kotlinx.io.*

val DNS_PORT = 53

object DnsFlags {
    /** A one bit field that specifies whether this message is a query (0), or a response (1). */
    val QR = 1

    /** Authoritative Answer - this bit is valid in responses and specifies that the responding name server is an
    authority for the domain name in question section. */
    val AA = 1 shl 5

    /** TrunCation - specifies that this message was truncate due to length greater than that permitted on the
    transmission channel. */
    val TC = 1 shl 6

    /** Recursion Desired - this bit may be set in a query and is copied into the response. If RD is set, it directs
    the name server to pursue the query recursively. Recursive query support is optional. */
    val RD = 1 shl 7

    /** Recursion Available - this be is set or cleared in a response, and denotes whether recursive query support is
    available in the name server. */
    val RA = 1 shl 8

    val OPCODE_SHIFT = 1
    val OPCODE_MASK = 0xf
    val OPCODE_QUERY = 0 // standard query, the only one supported

    val RCODE_SHIFT = 12
    val RCODE_MASK = 0xf
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
    (DnsFlags.QR or (code shl DnsFlags.RCODE_SHIFT)).toUShort()

class DnsMessage(
    val id: UShort,
    val flags: UShort,
    val question: DnsQuestion? = null,
    val answer: List<DnsAnswer> = emptyList(),
    val authority: List<DnsAnswer> = emptyList(),
    val additional: List<DnsAnswer> = emptyList()
) {
    val opCode: Int get() = (flags.toInt() shr DnsFlags.OPCODE_SHIFT) and DnsFlags.OPCODE_MASK
    val rCode: Int get() = (flags.toInt() shr DnsFlags.RCODE_SHIFT) and DnsFlags.RCODE_MASK

    @OptIn(ExperimentalStdlibApi::class)
    override fun toString(): String = buildString {
        append('#')
        append(id.toHexString())
        val f = flags.toInt()
        if ((f and DnsFlags.QR) != 0) {
            append(" Response")
        } else {
            append(" Query")
        }
        if ((f and DnsFlags.AA) != 0) {
            append(" AA")
        }
        if ((f and DnsFlags.TC) != 0) {
            append(" TC")
        }
        if ((f and DnsFlags.RD) != 0) {
            append(" RD")
        }
        if ((f and DnsFlags.RA) != 0) {
            append(" RA")
        }
        append(" OP")
        append(opCode)
        append(' ')
        append(rCode.toDnsRCodeString())
        question?.let {
            append(' ')
            append(it)
        }
        answer.forEach {
            append(' ')
            append(it)
        }
        authority.forEach {
            append(" NS")
            append(it)
        }
        additional.forEach {
            append(" AR")
            append(it)
        }
    }
}

class DnsName(val label: ByteArray, val next: DnsName? = null) {
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
        const val POINTER_BITS = 0xc0
    }
}

enum class DnsType(val code: UShort) {
    A(1), NS(2), CNAME(5), SOA(6), PTR(12), MX(15), TXT(16), ANY(255);

    constructor(code: Int) : this(code.toUShort())
}

fun UShort.toDnsTypeString(): String {
    val code = this
    val type = DnsType.entries.find { it.code == code }
    if (type != null) return type.name
    return "T$code"
}

enum class DnsClass(val code: UShort) {
    IN(1);

    constructor(code: Int) : this(code.toUShort())
}

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
        append('?')
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
    val rData: ByteArray
) {
    override fun toString(): String = buildString {
        append('=')
        append(name)
        append(' ')
        append(aType.toDnsTypeString())
        append(' ')
        append(aClass.toDnsClassString())
        append(" TTL ")
        append(ttl)
        append(' ')
        append(rData.toHexString())
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
    if ((len and DnsName.POINTER_BITS) == DnsName.POINTER_BITS) {
        return getNameAt(len and DnsName.POINTER_BITS.inv())
    }
    val name = DnsName(readByteArray(len), readDnsName())
    putNameAt(offset, name)
    return name
}

fun DnsPacket.readDnsQuestion(): DnsQuestion {
    val qName = readDnsName() ?: throw DnsProtocolFormatException("Empty name")
    val qType = readUShort()
    val qClass = readUShort()
    return DnsQuestion(qName, qType, qClass)
}

fun DnsPacketBuilder.writeDnsQuestion(question: DnsQuestion) {
    // todo:
}

fun DnsPacket.readDnsAnswer(): DnsAnswer {
    val name = readDnsName() ?: throw DnsProtocolFormatException("Empty name")
    val aType = readUShort()
    val aClass = readUShort()
    val ttl = readUInt()
    val rdLen = readUShort().toInt()
    val rData = readByteArray(rdLen)
    return DnsAnswer(name, aType, aClass, ttl, rData)
}

fun DnsPacketBuilder.writeDnsAnswer(answer: DnsAnswer) {
    // todo:
}

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

    fun putNameAt(i: Int, name: DnsName) {
        names[i] = name
    }

    fun getNameAt(i: Int): DnsName =
        names[i] ?: throw DnsProtocolFormatException("Unknown name offset $i")
}

fun DnsMessage.buildMessagePacket(): Source =
    buildPacket { DnsPacketBuilder(this).writeDnsMessage(this@buildMessagePacket) }

class DnsPacketBuilder(val sink: Sink) {
    var offset = 0

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
}