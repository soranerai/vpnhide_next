/* AUTO-GENERATED from data/interfaces.toml — do not edit by hand. Regenerate with: python3 scripts/codegen-interfaces.py */
#ifndef VPNHIDE_GENERATED_IFACE_LISTS_H
#define VPNHIDE_GENERATED_IFACE_LISTS_H

#ifdef __KERNEL__
# include <ktypes.h>
#else
# include <ctype.h>
# include <stdbool.h>
# include <stddef.h>
# include <string.h>
#endif

/* Manual tolower to avoid ctype.h dependency in minimal KPM */
/* Manual string helpers for minimal KPM */
static inline size_t vpnhide_strlen(const char *s) {
    size_t len = 0;
    while (s[len]) len++;
    return len;
}
static inline int vpnhide_strncmp(const char *s1, const char *s2, size_t n) {
    while (n && *s1 && (*s1 == *s2)) {
        s1++; s2++; n--;
    }
    if (n == 0) return 0;
    return *(unsigned char *)s1 - *(unsigned char *)s2;
}
static inline void *vpnhide_memchr(const void *s, int c, size_t n) {
    const unsigned char *p = s;
    while (n--) {
        if (*p == (unsigned char)c) return (void *)p;
        p++;
    }
    return 0;
}
static inline void *vpnhide_memmove(void *dest, const void *src, size_t n) {
    unsigned char *d = dest;
    const unsigned char *s = src;
    if (d < s) {
        while (n--) *d++ = *s++;
    } else {
        d += n; s += n;
        while (n--) *--d = *--s;
    }
    return dest;
}
#define strlen vpnhide_strlen
#define strncmp vpnhide_strncmp

static inline int vpnhide_tolower(int c) {
    if (c >= 'A' && c <= 'Z') return c + ('a' - 'A');
    return c;
}
#define tolower vpnhide_tolower

static inline bool vpnhide_iface_starts_with_ci(
	const char *name, const char *prefix)
{
	size_t i;
	for (i = 0; prefix[i]; i++) {
		if (!name[i])
			return false;
		if (tolower((unsigned char)name[i]) !=
		    (unsigned char)prefix[i])
			return false;
	}
	return true;
}

static inline bool vpnhide_iface_starts_with_then_digits_ci(
	const char *name, const char *prefix)
{
	size_t i;
	if (!vpnhide_iface_starts_with_ci(name, prefix))
		return false;
	i = strlen(prefix);
	if (!name[i])
		return false;
	for (; name[i]; i++)
		if (name[i] < '0' || name[i] > '9')
			return false;
	return true;
}

static inline bool vpnhide_iface_equals_ci(
	const char *name, const char *other)
{
	size_t i;
	for (i = 0; other[i]; i++) {
		if (!name[i])
			return false;
		if (tolower((unsigned char)name[i]) !=
		    (unsigned char)other[i])
			return false;
	}
	return name[i] == '\0';
}

static inline bool vpnhide_iface_contains_ci(
	const char *name, const char *needle)
{
	size_t nlen = strlen(needle);
	size_t i, j;
	if (nlen == 0)
		return true;
	for (i = 0; name[i]; i++) {
		for (j = 0; j < nlen; j++) {
			if (!name[i + j])
				return false;
			if (tolower((unsigned char)name[i + j]) !=
			    (unsigned char)needle[j])
				break;
		}
		if (j == nlen)
			return true;
	}
	return false;
}

static inline bool vpnhide_iface_is_vpn(const char *name)
{
	if (!name || !name[0])
		return false;
	/* OpenVPN, WireGuard userspace, Tailscale, generic tunneling */
	if (vpnhide_iface_starts_with_ci(name, "tun"))
		return true;
	/* OpenVPN bridged */
	if (vpnhide_iface_starts_with_ci(name, "tap"))
		return true;
	/* WireGuard kernel */
	if (vpnhide_iface_starts_with_ci(name, "wg"))
		return true;
	/* PPTP / L2TP PPP tunnels */
	if (vpnhide_iface_starts_with_ci(name, "ppp"))
		return true;
	/* Android built-in IPsec VPN */
	if (vpnhide_iface_starts_with_ci(name, "ipsec"))
		return true;
	/* kernel IPsec XFRM framework */
	if (vpnhide_iface_starts_with_ci(name, "xfrm"))
		return true;
	/* Apple-style, rare on Android */
	if (vpnhide_iface_starts_with_ci(name, "utun"))
		return true;
	/* L2TP */
	if (vpnhide_iface_starts_with_ci(name, "l2tp"))
		return true;
	/* GRE tunnels */
	if (vpnhide_iface_starts_with_ci(name, "gre"))
		return true;
	/* catch-all for renamed clients (myvpn0, vpn-client, xvpn1, ...) */
	if (vpnhide_iface_contains_ci(name, "vpn"))
		return true;
	/* Anonymous netdev / renamed tunnel using the kernel's default naming pattern (e.g. `ip link set tun0 name if33` from issue #86). Does NOT match `ifb<N>` — those are kernel intermediate-functional-block traffic-shaping ifaces (different shape: `if` + letter, not + digit). */
	if (vpnhide_iface_starts_with_then_digits_ci(name, "if"))
		return true;
	return false;
}

#endif /* VPNHIDE_GENERATED_IFACE_LISTS_H */
