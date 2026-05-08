_2026-05-09_

## English

Replaced easily detectable `/proc` configuration nodes with a private `miscdevice` interface (`/dev/vpnhide_ctrl`). This prevents simple fingerprinting of the module's target lists by untrusted apps. Updated the entire userspace stack (CLI tool and Android app) to support the new stealthy configuration protocol. Improved boot-time UID resolution with robust support for Work Profiles. Reworked apps sorting logic.

## Русский

Легко обнаруживаемые файлы конфигурации в `/proc` заменены на приватный интерфейс `miscdevice` (`/dev/vpnhide_ctrl`). Это редотвращает простое обнаружение списков целей модуля (fingerprinting) обычными приложениями. Весь пользовательский стек (утилита и Android-приложение) обновлен для работы с новым скрытным протоколом конфигурации. Улучшена логика определения UID при загрузке, включая надежную поддержку Work Profile. Переработана логика сортировки приложений.
