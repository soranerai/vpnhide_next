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

**Основные отличия от оригинала:**
*   **Отказ от поддержки старых архитектур**: Поддерживается только arm64.
*   **Глубокий редизайн и оптимизация**: Полностью переработанный интерфейс (skeleton, async loading) и оптимизированный код.
*   **Гибкая сортировка**: Добавлена возможность нормальной сортировки приложений.
*   **Сокрытие анонимных маршрутов TUN**: Исключение TUN из запросов маршрутов.
*   **Полная переработка блокировки портов**: Rule-based механизм блока доступа приложений к портам. Логика блокировки доступа перенесена с iptables в ядро.
*   **Переход приложения на БД**: Правила зеркально хранятся в БД приложения.
*   **Максимальная скрытность**: Полный отказ от файлов в `/proc/`, которые доступны всем приложениям, что исключает обнаружение модуля через файловую систему.
*   **Запрет бинда к tun0**: Запрет целевым приложениям биндиться к tun0.
*   **Поддержка рабочих профилей**: Вы можете полноценно управлять приложениями из рабочих профилей.
*   **Вырезано сокрытие приложений**: Используйте HMA-OSS (не пожалеете).
*   **Возможность скрывать кастомные интерфейсы**: Можно добавить любые кастомные префиксы для скрытия.

**Этот форк полностью сфокусирован на kernel-level и LSPosed. Поддержка Zygisk была удалена.**

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
