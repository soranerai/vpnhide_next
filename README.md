<p align="center">
  <img src="assets/logo.png" width="200" alt="VPNHide Next" />
</p>

<h1 align="center">VPNHide Next</h1>

<p align="center">Скрывает активное VPN-соединение на Android от выбранных приложений.</p>

<p align="center">
  <a href="https://github.com/soranerai/vpnhide_next/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/soranerai/vpnhide_next/ci.yml?label=CI" alt="CI"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases/latest"><img src="https://img.shields.io/github/v/release/soranerai/vpnhide_next" alt="Release"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases"><img src="https://img.shields.io/github/downloads/soranerai/vpnhide_next/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center"><strong><a href="README.en.md">English version</a></strong></p>

**vpnhide** — это инструмент для скрытия использования VPN от приложений на Android. Он делает VPN-соединение невидимым даже для тех сервисов, которые специально пытаются его обнаружить (например, банковские клиенты, стриминговые платформы или приложения с территориальными ограничениями).

---
### Информация о проекте
Это форк проекта [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide/). Проект был отделен от апстрима из-за внесения значительных изменений.

**Основные отличия от оригинала (вкратце):**
*   **Отказ от Zygisk**: Модуль полностью сфокусирован на скрытности без инъекций в процессы (только kmod + LSPosed).
*   **Блокировка портов на уровне ядра**: Блокировка loopback-соединений перенесена из iptables в хук ядра `security_socket_connect`.
*   **Хирургическая мимикрия в LSPosed**: Подмена VPN на свойства физ. сети в `system_server` вместо подозрительного стирания данных.
*   **Новые нативные векторы**: Скрытие `getsockname`, MTU/MSS clamping, setsockopt биндов и правил RPDB.
*   **16+ новых продвинутых векторов защиты**: В отличие от оригинала, закрывающего только базовые списки интерфейсов, Next нативно маскирует:
    1. Локальные адреса сокетов (`getsockname` IPv4/IPv6 в `inet_getname`).
    2. Прямые бинды к VPN (`SO_BINDTODEVICE` в `sock_setsockopt`).
    3. MTU и MSS clamping (`getsockopt` / `TCP_MAXSEG` / `IP_MTU`).
    4. UDP Path MTU Discovery (`IP_MTU_DISCOVER` / предотвращение ошибок `EMSGSIZE`).
    5. Занятость прокси/VPN-портов на localhost (`bind()` на 127.0.0.1 через `security_socket_bind`).
    6. Правила маршрутизации политик RPDB (`RTM_GETRULE` в Netlink).
    7. События Java-коллбеков (`NetworkCallback` в `system_server`).
    8. Wi-Fi параметры `WifiInfo` (SSID/BSSID/IP-реставрация), NetID сетей и другие низкоуровневые утечки.
*   **Максимальный stealth**: Полный отказ от ProcFS (файлов в `/proc/`) в пользу защищенного misc-устройства `/dev/vpnhide_ctrl`.
*   **Поддержка рабочих профилей**: Полноценное разделение приложений и рабочих профилей.
*   **Современная архитектура**: База данных Room (SQLite) с мгновенным авто-применением правил на лету.
*   **Только ARM64**: Полный отказ от старых платформ и оптимизация под arm64-v8a.
*   **Мониторинг и статистика перехватов (Native & Framework)**: Сбор детальной статистики блокировок и подмен в реальном времени, с выводом на главный экран и раскладкой по конкретным Java-методам и нативным syscall-векторам.
*   **Автоматическое сокрытие приложений**: Автоматическое сокрытие VPN приложений от LSPosed таргетов.

**Этот форк полностью сфокусирован на kernel-level и LSPosed. Поддержка Zygisk была удалена.**

### Детальное сравнение с оригиналом (подробно)

| Вектор обнаружения / Характеристика | Оригинал vpnhide (okhsunrog) | VPNHide Next (Этот форк) | Подход к сокрытию и преимущества |
| :--- | :--- | :--- | :--- |
| **Уровень работы & След в процессе** | Zygisk (инъекция в целевой процесс) / LSPosed / kmod | **Только kmod (ядро) + LSPosed (system_server)** | **Абсолютная скрытность**: Полное отсутствие инъекций и хуков внутри целевого процесса. Исключает обнаружение продвинутым Anti-Tamper/Anti-Cheat мониторингом памяти (MirPay, банковские приложения). |
| **Философия Java-сокрытия (LSPosed в system_server)** | **Топорное вырезание (Interface Cutting)**: Интерфейсы и маршруты VPN просто удаляются/зануляются в структурах. | **Хирургическая мимикрия (Surgical Mimicry)**: Подменяет имя `tun0` на активный физ. интерфейс (`wlan0`/`rmnet_data0`), клонирует и проецирует физ. `LinkAddresses`, `Dnses`, `Domains`, `Mtu` и маршруты. | **Имитация реального подключения**: Целевое приложение видит абсолютно легитимное физическое подключение без аномалий (вроде отсутствия DNS или пустого списка интерфейсов). |
| **Обнаружение портов (Localhost / Loopback)** | Внешний модуль `portshide` через правила `iptables`. | **Встроенный хук ядра `security_socket_connect` (Hook 12)** | **Нулевой сетевой след**: Блокировка портов (TCP/UDP, IPv4/IPv6, loopback subnets, wildcard) вшита в ядро. Нет подозрительных пользовательских цепочек в `iptables`. |
| **Локальный адрес сокета (`getsockname`)** | Пропускается (утечка локального IP-адреса VPN-шлюза). | **Kernel-level подмена через `inet_getname` / `inet6_getname` (Hook 13)** | **Мимикрия сокетов**: Возвращает приложению реальный физический IP устройства (динамически синхронизируется демоном) вместо адреса VPN-туннеля. |
| **Прямой бинд к VPN (`setsockopt` SO_BINDTODEVICE)** | Пропускается (приложение может принудительно забиндиться на VPN). | **Саботаж бинда в `sock_setsockopt` (Hook 2b)** | **Защита от обхода**: Обнуляет длину привязки при попытке бинда к VPN-интерфейсу. Ядро считает это «удалением привязки» и возвращает приложению `0` (Success). |
| **Обнаружение MTU/MSS Clamping & UDP Path MTU** | Пропускается (низкий MTU VPN-туннеля, например 1400, или ошибки `EMSGSIZE` при отправке UDP-пакетов выдают его наличие). | **Spoofing MTU/MSS & UDP PMTU в `getsockopt` / `setsockopt` / `sock_common_getsockopt` (Hooks 2c, 2d, 1.6.1)** | **Мимикрия размера пакета**: Подменяет `IP_MTU`/`IPV6_MTU` на 1500, `TCP_MAXSEG` на 1460, и перехватывает `setsockopt` с `IP_MTU_DISCOVER` (PMTUD), предотвращая ошибки отправки `EMSGSIZE` и маскируя накладные расходы VPN-туннеля. |
| **Обнаружение proxy/VPN портов (`bind()` на localhost)** | Пропускается (приложение может обнаружить активные прокси/VPN листенеры по ошибке `EADDRINUSE` на известных портах). | **Kernel-level маскировка через `security_socket_bind` (Hook 14)** | **Маскировка прокси-листенеров**: Перехватывает `bind()` на `127.0.0.1` для известных портов (SOCKS, HTTP, DNS). Если порт уже занят VPN/прокси, ядро возвращает приложению `0` (Success), позволяя повторно "привязать" сокет и полностью скрывая листенер. |
| **Политики маршрутизации (RPDB / Netlink)** | Пропускается. | **Фильтрация `RTM_GETRULE` (`fib_nl_fill_rule` / Hook 7b)** | Скрывает системные правила маршрутизации политик (policy routing) от целевых приложений. |
| **Запросы к DNS / DNS Leak** | Возможна утечка адресов VPN DNS-серверов в LinkProperties. | **Сверхточная фильтрация DNS** | Полностью вырезает утечки DNS-серверов VPN, заменяя их физическими DNS или Google Public DNS (8.8.8.8). |
| **Утечки `NetworkCallback`** | Пропускаются. | **Полное подавление VPN-коллбеков в system_server** | Исключает утечку событий VPN-интерфейса в коллбеках Java API (например, `onAvailable`, `onCapabilitiesChanged`). |
| **Сканирование Wi-Fi (WifiInfo Redaction)** | Пропускается. | **Восстановление физ. IP/SSID/BSSID (AOSP 12+)** | Обходит защитные эвристики приложений (например, МТС), восстанавливая реальные параметры сети, скрытые AOSP без геолокации. |
| **Уникальный NetID сети** | Пропускается (VPN выдает себя отличающимся netId). | **Динамическая подмена netId в system_server** | Подменяет VPN netId на netId физической сети для исключения перекрестных утечек. |
| **Изоляция и защита в FS** | Создает файлы `/proc/vpnhide_targets` и `/proc/vpnhide_debug` (доступны для проверки FS). | **Полный ProcFS-stealth**. Связь через misc-устройство `/dev/vpnhide_ctrl` | Нет файлов в `/proc/`. misc-устройство защищено правами `0660` (root/system) и невидимо для untrusted приложений. |
| **База данных правил и запуск** | Текстовые файлы конфигурации, медленный парсинг при старте. | **Room (SQLite) БД с inotify FileObservers** | Мгновенный запуск и применение правил «на лету» без IPC-задержек. |
| **Рабочие профили (Work Profiles)** | Отсутствуют. | **Полная нативная поддержка** | Разделение приложений из рабочих профилей с удобной фильтрацией. |
| **Сбор статистики перехватов** | Отсутствует. | **Полноценная статистика Native & Framework** | **Полная прозрачность**: Наглядный сбор статистики в реальном времени с ленивой загрузкой и детализацией до конкретных заблокированных/подмененных API (имя Java-хука и тип нативного вектора ioctl/netlink/getsockname/connect). |


### Архитектура
*   **`kmod`** — модуль ядра (рекомендуется), работающий вне процесса приложения. Требования: GKI + ARM64-v8a.
*   **`lsposed`** — фильтрация Binder-транзакций в `system_server`.

### Установка
1.  Установите `vpnhide.apk` и включите модуль в LSPosed (scope: System Framework).
2.  Перезагрузите устройство.
3.  Установите модуль ядра (`kmod`) через приложение.
4.  Выберите приложения для защиты во вкладке «Защита» и сохраните настройки.

### Скриншоты
| Дашборд | Список приложений | Сортировка | Диагностика |
|:-:|:-:|:-:|:-:|
| <img src="assets/screenshots/Dashboard.jpg" width="200"> | <img src="assets/screenshots/AppSelector.jpg" width="200"> | <img src="assets/screenshots/SortMenu.jpg" width="200"> | <img src="assets/screenshots/Diagnostics.jpg" width="200"> |
| **Массовые правила портов** | **Локальные правила портов** | **Валидация правил портов** | **FAQ** |
| <img src="assets/screenshots/Bulk%20edit%20rules.jpg" width="200"> | <img src="assets/screenshots/Local%20ports%20edit.jpg" width="200"> | <img src="assets/screenshots/Duplicate%20and%20redutant%20protection.jpg" width="200"> | <img src="assets/screenshots/FAQ.jpg" width="200"> |
| **Кастомные префиксы Tun** | **Изоляция хуков** | | |
| <img src="assets/screenshots/Custom%20tun%20interfaces.jpg" width="200"> | <img src="assets/screenshots/Hook%20isolation.jpg" width="200"> | | |
