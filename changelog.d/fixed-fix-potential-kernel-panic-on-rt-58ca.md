_2026-05-25_

## English

Fix potential kernel panic on rt_fill_info hook, and implement stealth getsockopt spoofing via sock_common_getsockopt for IP_MTU, IPV6_MTU, and TCP_MAXSEG to prevent detection of MTU/MSS clamping.

## Русский

Устранено возможное падение ядра на хуке rt_fill_info, а также реализована маскировка getsockopt через sock_common_getsockopt для IP_MTU, IPV6_MTU и TCP_MAXSEG для предотвращения обнаружения заниженного MTU/MSS.
