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

> Шрифты (Space Grotesk, JetBrains Mono) зашиты в сервер (`resources/admin/fonts/*.woff2`), иконки —
> инлайн-SVG. Консоль полностью работает офлайн, без обращений к внешним CDN.

> ⚠️ Метаданные содержат `accountId` (это e-mail) и удерживаются в аудит-логе (последние 2000
> событий). Для single-user self-host оператор и есть субъект данных — приемлемо. Admin-токен
> ходит в заголовке `X-Admin-Token` открытым текстом: обязательно поставьте TLS-терминатор
> (см. ниже), иначе токен виден в сети.

## Безопасность в проде

- Задайте устойчивый `SKERRY_JWT_SECRET` (иначе токены инвалидируются при рестарте) и непустой `SKERRY_ADMIN_TOKEN`.
- Бэкап = файл SQLite (`/data`) или дамп PostgreSQL; данные зашифрованы, но это ваша точка восстановления.
- Сам сервер слушает cleartext HTTP — TLS терминируется обратным прокси (ниже). Полезная нагрузка
  и так E2E-зашифрована (zero-knowledge), SRP безопасен поверх cleartext, но **админ-токен и метаданные
  (включая `accountId` = e-mail) идут открыто** — без TLS они видны в сети. Для публичного хоста TLS обязателен.

### TLS-терминатор

Клиент указывает `https://…` — WebSocket `/sync` автоматически переключается на `wss://` (тот же хост).

**Caddy** (автоматический Let's Encrypt, проще всего):

```caddy
sync.example.com {
    reverse_proxy localhost:8080
}
```

**nginx** (сертификат свой/Certbot; важно пробросить апгрейд WebSocket для `/sync`):

```nginx
server {
    listen 443 ssl;
    server_name sync.example.com;
    ssl_certificate     /etc/letsencrypt/live/sync.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sync.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        # WebSocket /sync (live-pull): без этих двух заголовков realtime-уведомления не работают.
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h; # /sync — долгоживущее соединение, не рвать по таймауту
    }
}
```

Привяжите сервер к loopback (`SKERRY_HOST=127.0.0.1`), чтобы 8080 не торчал в сеть в обход прокси.

> **Self-host в локальной сети без TLS** допустим осознанно: трафик E2E-зашифрован, метаданные
> остаются в доверенной LAN. Android-клиент разрешает cleartext (`network_security_config.xml`).
> Как только хост доступен извне — ставьте TLS.

## Тесты

```bash
./gradlew :server:test
```

Покрывают LWW-конфликты, SRP-роундтрип, JWT, и полный HTTP-флоу (register → вход → push/pull →
devices → pairing → admin).
