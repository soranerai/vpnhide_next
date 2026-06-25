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

> [!WARNING]
> **Этот форк использует крайне агрессивные методы сокрытия на уровне ядра и фреймворка**
> Стабильная работа на абсолютно всех устройствах, прошивках и версиях ядер **не гарантируется и не может быть гарантирована**.
> Согласно лицензии MIT, ПО предоставляется «как есть» (AS IS), без каких-либо гарантий. Автор не несёт ответственности за возможные сбои, бутлупы (bootloop) или панику ядра (kernel panic). 

---
### Информация о проекте
Это форк проекта [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide/). Проект был отделен от апстрима из-за внесения значительных изменений и разной философии. 
Философия этого проекта - перекрыть ВСЕ прямые и косвенные векторы на корню. 

**Основные отличия от оригинала (вкратце):**
*   **Блокировка портов на уровне ядра**: Блокировка loopback-соединений перенесена из iptables в хук ядра `security_socket_connect`.
*   **16+ новых продвинутых векторов защиты**: Скрывается все, что только можно скрыть.
*   **Иная архитектура обмена данными**: Полный отказ от ProcFS (файлов в `/proc/`) в пользу misc-устройства `/dev/vpnhide_ctrl`.
*   **Поддержка рабочих профилей**: Полноценное разделение приложений и рабочих профилей.
*   **Единый источник истины**: На диске - единый JSON файл со всей конфигурацией. Во время рантайма - все данные проходят через ядро.
*   **Мониторинг и статистика перехватов (Native & Framework)**: Сбор детальной статистики блокировок и подмен в реальном времени.
*   **Автоматическое сокрытие приложений**: Автоматическое сокрытие VPN приложений от LSPosed таргетов.

### Скриншоты
| Дашборд | Список приложений | Сортировка | Диагностика |
|:-:|:-:|:-:|:-:|
| <img src="assets/screenshots/Dashboard.jpg" width="200"> | <img src="assets/screenshots/AppSelector.jpg" width="200"> | <img src="assets/screenshots/SortMenu.jpg" width="200"> | <img src="assets/screenshots/Diagnostics.jpg" width="200"> |
| **Массовые правила портов** | **Локальные правила портов** | **Валидация правил портов** | **FAQ** |
| <img src="assets/screenshots/Bulk%20edit%20rules.jpg" width="200"> | <img src="assets/screenshots/Local%20ports%20edit.jpg" width="200"> | <img src="assets/screenshots/Duplicate%20and%20redutant%20protection.jpg" width="200"> | <img src="assets/screenshots/FAQ.jpg" width="200"> |
| **Кастомные префиксы Tun** | **Изоляция хуков** | | |
| <img src="assets/screenshots/Custom%20tun%20interfaces.jpg" width="200"> | <img src="assets/screenshots/Hook%20isolation.jpg" width="200"> | | |
