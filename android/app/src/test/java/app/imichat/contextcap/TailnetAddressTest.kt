package app.imichat.contextcap

import app.imichat.contextcap.TailnetAddress.Interface
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TailnetAddressTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun `Tailscale の v6 を持つインターフェースの v4 を返す`() {
        val got = TailnetAddress.pick(
            listOf(
                Interface("wlan0", listOf(ip("192.168.68.86"), ip("fe80::1"))),
                Interface("tun0", listOf(ip("fd7a:115c:a1e0::ab12:2b1a"), ip("100.103.43.26"))),
            ),
        )
        assertEquals(ip("100.103.43.26"), got)
    }

    @Test
    fun `キャリアの CGNAT は Tailscale と見なさない`() {
        // モバイル回線にも 100.64.0.0/10 が割り当たることがある
        val got = TailnetAddress.pick(
            listOf(Interface("rmnet1", listOf(ip("100.80.1.2"), ip("2400:2200::1")))),
        )
        assertNull(got)
    }

    @Test
    fun `Tailscale の v6 があっても v4 が CGNAT 帯でなければ返さない`() {
        val got = TailnetAddress.pick(
            listOf(Interface("tun0", listOf(ip("fd7a:115c:a1e0::1"), ip("10.0.0.1")))),
        )
        assertNull(got)
    }

    @Test
    fun `Tailscale が無ければ null`() {
        assertNull(TailnetAddress.pick(listOf(Interface("wlan0", listOf(ip("192.168.0.2"))))))
        assertNull(TailnetAddress.pick(emptyList()))
    }
}
