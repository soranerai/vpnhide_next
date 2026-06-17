_2026-06-17_

## English

Fix SO_BINDTODEVICE leak on kernels without sock_getsockopt/sock_setsockopt (add sk_getsockopt/sk_setsockopt primary hooks with conditional registration)

## Русский

Исправлена утечка SO_BINDTODEVICE на ядрах без sock_getsockopt/sock_setsockopt: добавлены sk_getsockopt/sk_setsockopt как основные хуки с условной регистрацией
