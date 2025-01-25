import java.lang.Exception
import java.net.Inet4Address
import java.net.InetAddress

actual fun nativeResolveHostAddr(host: String): ResolveResult {
    try {
        val list = ArrayList<IpAddressPrefix>()
        for (addr: InetAddress in InetAddress.getAllByName(host)) {
            if (addr !is Inet4Address) continue
            list += IpAddressPrefix(prefix = addr.address)
        }
        return ResolveResult.Ok(list)
    } catch (e: Exception) {
        return ResolveResult.Err(e.toString())
    }
}