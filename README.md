<p align="center">
  <img src="assets/logo.png" width="200" alt="VPNHide Next" />
</p>

<h1 align="center">VPNHide Next</h1>

<p align="center">Скрывает активное VPN-соединение на Android от выбранных приложений.</p>

<p align="center">
  <a href="https://github.com/soranerai/vpnhide_next/releases/latest"><img src="https://img.shields.io/github/v/release/soranerai/vpnhide_next" alt="Release"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases"><img src="https://img.shields.io/github/downloads/soranerai/vpnhide_next/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center"><strong><a href="README.en.md">English version</a></strong></p>

> [!WARNING]
> **Этот форк использует крайне агрессивные методы сокрытия на уровне ядра и фреймворка**
> Стабильная работа на абсолютно всех устройствах, прошивках и версиях ядер **не гарантируется и не может быть гарантирована**.
> Согласно лицензии MIT, ПО предоставляется «как есть» (AS IS), без каких-либо гарантий. Автор не несёт ответственности за возможные сбои, бутлупы (bootloop) или панику ядра (kernel panic). 

---
### Информация о проекте
Это форк проекта [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide/). Как и оригинал, он прячет активный VPN от выбранных приложений на трёх уровнях — хуки в `system_server` через LSPosed, нативный бэкенд — LKM и опциональная блокировка портов — но был отделён от апстрима из-за значительных изменений и разной философии.
Философия этого проекта — перекрыть ВСЕ прямые и косвенные векторы на корню, а не только самые очевидные.

**Основные отличия от оригинала (вкратце):**
*   **Блокировка портов на уровне ядра**: вместо отдельного iptables-модуля loopback-соединения к портам VPN-демона блокирует хук ядра `security_socket_connect` — без правил iptables и без ProcFS.
*   **Новые уровни защиты, которых в апстриме нет вообще**: см. раздел «Уровни защиты» ниже — подмена MTU/MSS/TCP_INFO, защита от GSO/PMTU-зондов, обнуление eBPF-статистики трафика, скрытие qdisc, защита от timing-атак и IPv6 link-local перебора.
*   **Иная архитектура обмена данными**: полный отказ от ProcFS (файлов в `/proc/`) в пользу misc-устройства `/dev/vpnhide_ctrl`.
*   **Поддержка рабочих профилей**: полноценное разделение приложений и рабочих профилей.
*   **Единый источник истины**: на диске — единый JSON файл со всей конфигурацией; во время рантайма все данные проходят через ядро.
*   **Автоматическое сокрытие приложений**: автоматическое сокрытие VPN-приложений от LSPosed-таргетов в рантайме, без необходимости пересохранений списков.

### Уровни защиты
Уровень выбирается на дашборде и на лету переключает набор активных хуков ядра — это компромисс между полнотой сокрытия и производительностью.

| Уровень | Что перекрывает |
|---|---|
| **Мин** | Перечисление интерфейсов и адресов (`getifaddrs`, `ioctl`, netlink, таблицы маршрутизации ядра) + блокировка VPN-портов при `bind()`/`connect()`. Не покрыто: параметры сокета (MTU/MSS/TCP_INFO), GSO/PMTU-зонды, eBPF-статистика трафика, `/sys/class/net`. |
| **Сред** | Всё из «Мин» + перехват `setsockopt`/`getsockopt`: MTU, MSS, `TCP_INFO`, `SO_BINDTODEVICE` и `SO_TIMESTAMPING` подменяются под физический интерфейс, `SO_MARK` не течёт наружу, GSO/PMTU-зонды нейтрализованы. |
| **Макс** | Всё из «Сред» + `/proc/net/{dev,if_inet6,fib_trie}` и `/sys/class/net` скрыты из файловой системы, eBPF-статистика трафика обнулена, UDP защищён от timing-атак, IPv6 link-local перебор заблокирован, VPN-qdisc скрыт. Покрывает все известные прямые и косвенные векторы обнаружения. |

### Насколько это ушло дальше оригинала
Апстрим закрывает порядка 25 векторов обнаружения (нативные syscall'ы, netlink, `/proc`, Java connectivity API). Этот форк проверяет те же базовые векторы («классический» уровень диагностики) и добавляет ещё два эшелона — **Advanced** и **Extreme** — итого **44 автоматические диагностические проверки** (37 нативных + 7 Java-уровня) во встроенном экране диагностики. Порядка 20 из них (подмена MSS/PMTU/TCP_INFO, обнуление eBPF-статистики трафика, скрытие qdisc, защита от timing-атак через UDP, защита от IPv6 link-local перебора, `RTM_GETLINK` trim-oracle и другие) в апстриме отсутствуют в принципе — это векторы, которые оригинал не пытается закрывать.

### Скриншоты
<div align="center">

| Дашборд | Список приложений | Статистика |
|:-:|:-:|:-:|
| <img src="assets/screenshots/dushboard.jpg" width="200"> | <img src="assets/screenshots/apps_picker.jpg" width="200"> | <img src="assets/screenshots/statistics_screen.jpg" width="200"> |
| **Настройки хуков** | **Настройки приложения** | **Диагностика** |
| <img src="assets/screenshots/hook_settings.jpg" width="200"> | <img src="assets/screenshots/app_settings.jpg" width="200"> | <img src="assets/screenshots/diagnostics_screen.jpg" width="200"> |

</div>


