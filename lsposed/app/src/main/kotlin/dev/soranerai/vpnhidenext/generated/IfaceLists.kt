// AUTO-GENERATED from data/interfaces.toml — do not edit by hand. Regenerate with: python3 scripts/codegen-interfaces.py

package dev.soranerai.vpnhidenext.generated

internal object IfaceLists {
    /** True if `name` looks like a VPN tunnel per data/interfaces.toml. */
    fun isVpnIface(name: String): Boolean {
        if (name.isEmpty()) return false
        val n = name.lowercase()
        // OpenVPN, WireGuard userspace, Tailscale, generic tunneling
        if (n.startsWith("tun") && !n.startsWith("tunl")) return true
        // OpenVPN bridged
        if (n.startsWith("tap")) return true
        // WireGuard kernel
        if (n.startsWith("wg")) return true
        // PPTP / L2TP PPP tunnels
        if (n.startsWith("ppp")) return true
        // Android built-in IPsec VPN
        if (n.startsWith("ipsec")) return true
        // kernel IPsec XFRM framework
        if (n.startsWith("xfrm")) return true
        // Apple-style, rare on Android
        if (n.startsWith("utun")) return true
        // L2TP
        if (n.startsWith("l2tp")) return true
        // catch-all for renamed clients (myvpn0, vpn-client, xvpn1, ...)
        if (n.contains("vpn")) return true
        // Anonymous netdev / renamed tunnel using the kernel's default naming pattern (e.g. `ip link set tun0 name if33` from issue #86). Does NOT match `ifb<N>` — those are kernel intermediate-functional-block traffic-shaping ifaces (different shape: `if` + letter, not + digit).
        if (n.startsWith("if") && n.length > 2 && n.substring(2).all { it.isDigit() }) return true
        return false
    }
}
