_2026-06-29_

## English

kmod: add two new hooks hardening against check_ipv6_link_local_bruteforce — inet6_bind link-local scope_id probe suppression (Hook 12d: intercepts AF_INET6 bind(fe80::, scope_id=VPN_idx) for target UIDs and returns ENODEV, hiding VPN interface indices from the Pass 1 blind bruteforce; kretprobe on inet6_bind since uaddr is already kernel-space by then) and a udpv6_sendmsg hook that suppresses the IPv6 NDP oracle and qdisc-flood detects

## Русский

kmod: добавлены два новых хука для защиты от check_ipv6_link_local_bruteforce — фильтрация IPv6 link-local зондирования через sin6_scope_id (Hook 12d: перехватывает bind(AF_INET6, {fe80::, scope_id=VPN_idx}) для целевых UID и возвращает ENODEV, скрывая индексы VPN-интерфейсов от Pass 1 bruteforce; kretprobe на inet6_bind, так как uaddr к этому моменту уже находится в kernel-space) и хук udpv6_sendmsg, подавляющий NDP-оракул и детект флуда очереди на IPv6 link-local адресах
