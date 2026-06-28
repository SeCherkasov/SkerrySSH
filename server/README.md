# Skerry Sync Server

Self-hosted, zero-knowledge E2E-синхронизация для Skerry (модель Vaultwarden). Сервер хранит
**только шифротекст** (обёрнутый dataKey, зашифрованные записи vault) и метаданные синхронизации.
Мастер-пароль, `masterKey` и `dataKey` никогда не покидают устройство и серверу недоступны.

> Лицензия: **AGPL-3.0** (см. `LICENSE`). Клиенты Skerry — GPL-3.0.
> Протокол, иерархия ключей и модель угроз: [`../docs/skerry-sync-design.md`](../docs/skerry-sync-design.md).

## Что внутри

- **Стек**: Kotlin + Ktor (Netty), Exposed, HikariCP. Аутентификация — SRP-6a (Nimbus) + JWT.
- **Хранилище**: SQLite по умолчанию (один файл, нулевая настройка); PostgreSQL — сменой `SKERRY_DB_URL`.
- **Крипто на сервере отсутствует** by design: сервер не умеет расшифровывать пользовательские данные.

## Быстрый старт

### Docker (рекомендуется)

```bash
# из корня репозитория
export SKERRY_JWT_SECRET="$(openssl rand -base64 48)"
export SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)"
docker compose up -d --build
```

Сервер поднимется на `http://localhost:8080`. Данные — в томе `skerry-data` (SQLite).
Переключение на PostgreSQL — раскомментируйте сервис `db` и postgres-переменные в `docker-compose.yml`.

### Локально (Gradle)

```bash
SKERRY_JWT_SECRET=dev-secret SKERRY_ADMIN_TOKEN=admin ./gradlew :server:run
```

Конфигурация — переменные окружения, полный список в [`.env.example`](.env.example).

## Эндпоинты

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/healthz` | liveness (для контейнера) |
| `POST` | `/auth/register` | регистрация: SRP-соль/верификатор + обёртка dataKey → токены |
| `POST` | `/auth/srp/challenge` → `/auth/srp/verify` | вход по SRP без передачи пароля |
| `POST` | `/auth/refresh` | ротация access/refresh токенов |
| `GET` | `/vault/keys` | `wrappedDataKey` для нового устройства |
| `GET` | `/vault/records?since={cursor}` | дельта зашифрованных записей |
| `PUT` | `/vault/records` | batch upsert с LWW (version, затем deviceId) |
| `WS` | `/sync` | push «появились изменения» (только курсор, без содержимого) |
| `GET/DELETE` | `/devices`, `/devices/{id}` | список устройств и отзыв |
| `POST` | `/pairing/start` (auth) → `/pairing/claim` | быстрый локальный паринг (вариант B) |
| `GET` | `/admin/health` | liveness (открыт) |
| `GET` | `/admin/stats`, `/admin/devices`, `/admin/activity` | агрегаты, список устройств, аудит-лог (под `SKERRY_ADMIN_TOKEN`) |
| `DELETE` | `/admin/devices/{id}?accountId=` | отзыв устройства из консоли |

Все шифроблобы (`blob`, `wrappedDataKey`, `encryptedDataKey`) передаются как base64.

## Админ-консоль

Статическая страница на `http://localhost:8080/console` (требует `SKERRY_ADMIN_TOKEN`) — единый
дашборд (макет `docs/skerry-sync-prototype.html`): **Overview** (аккаунты, устройства, записи,
суммарный размер шифроблобов), **Devices** (платформа, последняя синхронизация, версия курсора,
статус + отзыв), **Privacy boundary** (что сервер видит / не видит) и **Recent activity** (аудит-лог
событий). Zero-knowledge сохраняется: консоль видит только метаданные — событие, устройство, метку
платформы, курсоры и размер-агрегат, но не содержимое записей, мастер-пароль или `dataKey`.

> Шрифты и иконки (Space Grotesk, JetBrains Mono, Material Symbols) грузятся с Google Fonts CDN.
> Без интернета консоль работает, но иконки выродятся в текст. Для полностью офлайн-инсталляции
> шрифты можно зашить локально (техдолг).

> ⚠️ Метаданные содержат `accountId` (это e-mail) и удерживаются в аудит-логе (последние 2000
> событий). Для single-user self-host оператор и есть субъект данных — приемлемо. Admin-токен
> ходит в заголовке `X-Admin-Token` открытым текстом: обязательно поставьте TLS-терминатор
> (см. ниже), иначе токен виден в сети.

## Безопасность в проде

- Поставьте перед сервером TLS-терминатор (reverse proxy) — протокол подразумевает HTTPS.
- Задайте устойчивый `SKERRY_JWT_SECRET` (иначе токены инвалидируются при рестарте).
- Бэкап = файл SQLite (`/data`) или дамп PostgreSQL; данные зашифрованы, но это ваша точка восстановления.

## Тесты

```bash
./gradlew :server:test
```

Покрывают LWW-конфликты, SRP-роундтрип, JWT, и полный HTTP-флоу (register → вход → push/pull →
devices → pairing → admin).
