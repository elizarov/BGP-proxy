import java.lang.Exception
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.time.TimeSource

actual fun nativeResolveHostAddr(host: String): ResolveResult {
    try {
        val start = TimeSource.Monotonic.markNow()
        val list = ArrayList<IpAddress>()
        for (addr: InetAddress in InetAddress.getAllByName(host)) {
            if (addr !is Inet4Address) continue
            list += addr.address.toIpAddress()
        }
        return ResolveResult.Ok(list, elapsed = start.elapsedNow())
    } catch (e: Exception) {
        return ResolveResult.Err(e.toString())
    }
}