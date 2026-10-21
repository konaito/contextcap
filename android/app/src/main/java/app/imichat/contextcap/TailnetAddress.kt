package app.imichat.contextcap

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/// Tailscale が端末に割り当てたアドレスを探す。`RemoteServer` はここで見つけたアドレスだけで待ち受ける。
///
/// **100.64.0.0/10 だけで判定しない。** 携帯キャリアの CGNAT も同じ帯域を rmnet に割り当てるので、
/// それを掴むとモバイル回線側に口を開けてしまう。Tailscale の IPv6 は必ず
/// `fd7a:115c:a1e0::/48`（Tailscale 固有の ULA）なので、それを持つインターフェースを
/// Tailscale のものと見なし、同じインターフェースの IPv4 を返す。
///
/// 判定は純粋関数（`pick`）に分けてある。`NetworkInterface` を直接触る `current` は unit test しない。
object TailnetAddress {

    data class Interface(val name: String, val addresses: List<InetAddress>)

    /// Tailscale のインターフェースの IPv4。見つからなければ null（Tailscale が切れている）
    fun pick(interfaces: List<Interface>): InetAddress? {
        for (iface in interfaces) {
            if (iface.addresses.none(::isTailscaleV6)) continue
            return iface.addresses.firstOrNull { it is Inet4Address && isCgnat(it) } ?: continue
        }
        return null
    }

    /// 今の端末のインターフェースから探す。列挙に失敗したら null
    fun current(): InetAddress? = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        pick(
            ifaces.filter { it.isUp }.map { iface ->
                Interface(iface.name, iface.inetAddresses.toList())
            },
        )
    }.getOrNull()

    private fun isTailscaleV6(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        val b = address.address
        return b[0] == 0xfd.toByte() && b[1] == 0x7a.toByte() &&
            b[2] == 0x11.toByte() && b[3] == 0x5c.toByte() &&
            b[4] == 0xa1.toByte() && b[5] == 0xe0.toByte()
    }

    /// 100.64.0.0/10
    private fun isCgnat(address: Inet4Address): Boolean {
        val b = address.address
        return b[0] == 100.toByte() && (b[1].toInt() and 0xc0) == 0x40
    }
}
