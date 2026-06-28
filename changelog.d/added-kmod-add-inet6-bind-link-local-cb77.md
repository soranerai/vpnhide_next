_2026-06-28_

## English

kmod: add inet6_bind link-local scope_id probe suppression (Hook 12d) — intercepts AF_INET6 bind(fe80::, scope_id=VPN_idx) for target UIDs and returns ENODEV, hiding VPN interface indices from check_ipv6_link_local_bruteforce Pass 1 blind bruteforce; uses kretprobe on inet6_bind (called after move_addr_to_kernel, so uaddr is kernel-space)

## Русский

kmod: добавлена фильтрация IPv6 link-local зондирования через sin6_scope_id (Hook 12d) — перехватывает bind(AF_INET6, {fe80::, scope_id=VPN_idx}) для целевых UID и возвращает ENODEV, скрывая индексы VPN-интерфейсов от Pass 1 check_ipv6_link_local_bruteforce; kretprobe на inet6_bind (вызывается после move_addr_to_kernel, uaddr находится в kernel-space)
