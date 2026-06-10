# Skerry

Опенсорсный кроссплатформенный SSH-клиент: единое Kotlin Multiplatform ядро и Compose Multiplatform UI для Linux, Windows, macOS, Android и iOS/iPadOS.

> Статус: прототипирование завершено, идёт скаффолдинг кодовой базы. Первый релиз — после рабочего MVP.

## Возможности (MVP, Phase 1)

- SSH + SFTP + port forwarding
- Менеджер хостов и ключей
- Полноценный терминал
- Мастер-пароль + биометрия, локальное зашифрованное хранилище

Дальше: self-hosted E2E-синхронизация (zero-knowledge), сниппеты, AI-ассистент под политиками, Mosh/Telnet/serial. Подробности — в [docs/skerry-product-brief.md](docs/skerry-product-brief.md).

## Структура репозитория

```
shared/        # ядро KMP: ssh/, sftp/, vault/, sync/, terminal/, ai/
composeApp/    # UI (Compose Multiplatform): commonMain + androidMain/iosMain/desktopMain
server/        # self-hosted sync-сервер (Ktor)
docs/          # прототипы и проектные документы
```

HTML-прототипы в `docs/` открываются в браузере: desktop, mobile, tablet и админка sync-сервера.

## Принципы

- **Local-first** — всё работает без сервера.
- **Zero-knowledge** — мастер-пароль не покидает устройство.
- **AI under policy** — вывод сервера считается недоверенным; действия только после подтверждения.
- **Паритет платформ** — фича не готова, пока не работает везде.

## Лицензии

- Клиенты (`shared/`, `composeApp/`) — [GPL-3.0](LICENSE)
- Sync-сервер (`server/`) — AGPL-3.0 (см. `server/LICENSE`)
