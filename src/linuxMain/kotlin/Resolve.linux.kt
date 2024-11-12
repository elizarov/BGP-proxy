@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*

actual fun resolveHostAddr(host: String): ResolveResult {
    val list = ArrayList<IpAddressPrefix>()
    memScoped {
        val hints: addrinfo = alloc()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM
        val res: CPointerVar<addrinfo> = alloc()
        val err = getaddrinfo(host, null, hints.ptr, res.ptr)
        if (err != 0) return ResolveResult.Err("getaddrinfo error: ${gai_strerror(err)?.toKString()}")
        var cur = res.value
        while (cur != null) with (cur.pointed) {
            val addr = ai_addr!!.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr.toInt()
            var bits = 0
            for (i in 0..3) {
                bits = bits or (((addr shr (8 * i)) and 0xff) shl (8 * (3 - i)))
            }
            list += IpAddressPrefix(bits = bits)
            cur = ai_next
        }
        freeaddrinfo(res.value)
    }
    return ResolveResult.Ok(list)
}