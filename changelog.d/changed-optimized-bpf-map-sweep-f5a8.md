_2026-06-05_

## English

Optimized BPF map sweep: increased interval to 3 seconds, switched to deferrable work to prevent idle CPU wakeups, and added dynamic timer activation that pauses after 5 seconds of target inactivity. Also offloaded physical transport IP detection to an event-driven C daemon (`vpnhide-daemon`) listening to Netlink sockets, completely removing the legacy polling loops from `system_server` (Java) and `service.sh` (Bash).

## Русский

Оптимизирована очистка BPF-карт: интервал увеличен до 3 секунд, используется ленивый таймер (deferrable work) для снижения энергопотребления процессора, и добавлена динамическая приостановка работы при отсутствии активности целевых приложений более 5 секунд. Также перенесено определение IP физического транспорта в событийно-ориентированный демон на C (`vpnhide-daemon`), слушающий сокеты Netlink, что позволило полностью избавиться от периодических опросов (polling) в `system_server` (Java) и `service.sh` (Bash).
