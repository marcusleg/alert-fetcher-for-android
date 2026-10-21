package de.marcusleg.alertmanagerreceiver.util

import de.marcusleg.alertmanagerreceiver.server.WEBHOOK_PATH
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

object NetworkAddresses {
    data class Address(val host: String, val interfaceName: String, val kind: Kind) {
        val isIpv6: Boolean get() = host.contains(':')
    }

    enum class Kind(val label: String) { WIFI("Wi-Fi"), ETHERNET("Ethernet"), VPN("VPN"), MOBILE("Mobile"), OTHER("Network") }

    /** Addresses other machines can reach this phone on, IPv4 and Wi-Fi first. Link-local addresses are skipped. */
    fun list(): List<Address> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && (it is Inet4Address || it is Inet6Address) }
                    .map { Address(it.hostAddress.orEmpty().substringBefore('%'), nif.name, kindOf(nif.name)) }
            }
            .sortedWith(compareBy({ it.isIpv6 }, { it.kind.ordinal }, { it.host }))
    }.getOrDefault(emptyList())

    fun kindOf(name: String): Kind = when {
        name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> Kind.WIFI
        name.startsWith("eth") || name.startsWith("usb") -> Kind.ETHERNET
        name.startsWith("tun") || name.startsWith("wg") || name.startsWith("ppp") || name.startsWith("tailscale") -> Kind.VPN
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("seth") || name.startsWith("v4-rmnet") -> Kind.MOBILE
        else -> Kind.OTHER
    }

    fun hostPort(address: Address, port: Int): String =
        if (address.isIpv6) "[${address.host}]:$port" else "${address.host}:$port"

    fun webhookUrl(address: Address, port: Int): String = "http://${hostPort(address, port)}$WEBHOOK_PATH"
}
