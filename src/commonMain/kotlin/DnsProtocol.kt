
class DnsMessage(
    val id: UShort,
    val flags: UShort,
    val question: DnsQuestion? = null,
    val answer: List<DnsAnswer> = emptyList(),
    val authority: List<DnsAnswer> = emptyList(),
    val additional: List<DnsAnswer> = emptyList()
)

class DnsQuestion {
    
}

class DnsAnswer {

}