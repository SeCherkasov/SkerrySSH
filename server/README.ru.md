# Skerry Sync Server

[English](README.md) · **Русский**

Self-hosted, zero-knowledge E2E-синхронизация для [Skerry](../README.ru.md) (модель
Vaultwarden). Сервер хранит **только шифротекст** — обёрнутый `dataKey` и зашифрованные
записи vault — плюс метаданные синхронизации. Мастер-пароль, `masterKey` и `dataKey` никогда
не покидают устройство и серверу недоступны.

> Лицензия: **AGPL-3.0** (см. `LICENSE`). Клиенты Skerry — GPL-3.0.

## Что внутри

- **Стек**: Kotlin + Ktor (Netty), Exposed, HikariCP. Аутентификация — SRP-6a (Nimbus) + JWT.
- **Хранилище**: SQLite по умолчанию (один файл, нулевая настройка); PostgreSQL — сменой
  `SKERRY_DB_URL`.
- **Крипто на сервере отсутствует** by design: сервер не умеет расшифровывать пользовательские
  данные. При регистрации загружаются SRP-соль/верификатор и обёрнутый `dataKey`; вход — обмен
  SRP-6a, в котором сам пароль никогда не передаётся.

## Быстрый старт

### Docker (готовый образ, рекомендуется)

Мультиархитектурные образы (amd64 + arm64) публикуются на Docker Hub как
[`secherkasov/skerry-sync`](https://hub.docker.com/r/secherkasov/skerry-sync) — теги:
точная `<версия>`, `<major.minor>`, `latest`. Сервер релизится в своём собственном темпе,
независимо от версий клиентов Skerry.

```bash
docker run -d --name skerry-sync \
  -p 8080:8080 \
  -e SKERRY_JWT_SECRET="$(openssl rand -base64 48)" \
  -e SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)" \
  -v skerry-data:/data \
  secherkasov/skerry-sync:latest
```

Держите `SKERRY_JWT_SECRET` неизменным при пересоздании контейнера (сохраните его в
env-файл) — смена секрета инвалидирует все выданные токены.

### Docker Compose (сборка из исходников)

```bash
# из корня репозитория
export SKERRY_JWT_SECRET="$(openssl rand -base64 48)"
export SKERRY_ADMIN_TOKEN="$(openssl rand -hex 16)"
docker compose up -d --build
```

В обоих вариантах сервер поднимется на `http://localhost:8080`. Данные — в томе
`skerry-data` (SQLite). Переключение на PostgreSQL — раскомментируйте сервис `db` и
postgres-переменные в `docker-compose.yml`.

Контейнер работает от непривилегированного пользователя, отдаёт healthcheck `/healthz`,
а образ собирается с `-PserverOnly` — Android SDK не нужен. Рядом лежит
административный CLI: `docker exec skerry-sync skerry-admin --help`.

### Локально (Gradle)

```bash
SKERRY_JWT_SECRET=dev-secret SKERRY_ADMIN_TOKEN=admin ./gradlew :server:run -PserverOnly
```

## Конфигурация

Всё настраивается переменными окружения (модель «один `.env`»); шаблон с комментариями — в
[`.env.example`](.env.example). У всех значений разумные дефолты для локального запуска —
в проде *обязателен* только устойчивый `SKERRY_JWT_SECRET`.

| Переменная | Дефолт | Назначение |
|---|---|---|
| `SKERRY_HOST` | `0.0.0.0` | Интерфейс. За обратным прокси ставьте `127.0.0.1`. |
| `SKERRY_PORT` | `8080` | Порт. |
| `SKERRY_DB_URL` | `jdbc:sqlite:skerry-sync.db` | JDBC URL; `jdbc:postgresql://…` переключает драйвер на PostgreSQL. |
| `SKERRY_DB_USER` / `SKERRY_DB_PASSWORD` | *(пусто)* | Учётные данные БД (PostgreSQL). |
| `SKERRY_JWT_SECRET` | `dev-insecure-change-me` | Секрет подписи JWT. **С дефолтом сервер не стартует**, если не задан `SKERRY_DEV=1`. Смена секрета инвалидирует все выданные токены. |
| `SKERRY_JWT_ISSUER` | `skerry-sync` | Клейм `iss` в JWT. |
| `SKERRY_ADMIN_TOKEN` | *(пусто)* | Токен консоли оператора (`/console`, `/admin/*`). Пустой ⇒ админ-эндпоинты с данными закрыты. |
| `SKERRY_ACCESS_TTL` | `900` (15 мин) | Время жизни access-токена, секунды. |
| `SKERRY_REFRESH_TTL` | `2592000` (30 дней) | Время жизни refresh-токена, секунды. |
| `SKERRY_PAIRING_TTL` | `300` (5 мин) | Время жизни одноразовой сессии QR-паринга. |
| `SKERRY_TOMBSTONE_DAYS` | `90` | Срок хранения tombstone-записей до физической очистки. |
| `SKERRY_CORS_HOSTS` | *(пусто)* | Разрешённые CORS-источники через запятую. Пусто — CORS выключен (нативных клиентов он не касается). |
| `SKERRY_MAX_BODY_BYTES` | `4194304` (4 MiB) | Лимит тела запроса (защита от OOM/абьюза); сверх лимита — `413`. |
| `SKERRY_DEV` | *(не задана)* | `1` разрешает дефолтный JWT-секрет — только для локальной разработки. |
| `SKERRY_METRICS` | `off` | Prometheus `/metrics`: `off` (404), `token` (bearer), `open` (без токена). |
| `SKERRY_METRICS_TOKEN` | *(пусто)* | Bearer-токен для `SKERRY_METRICS=token`. С режимом `token` и пустым токеном сервер не стартует. |
| `SKERRY_METRICS_INVENTORY_SECONDS` | `60` | Интервал обновления инвентарных gauge (минимум 15, `0` отключает их). |

## Как работает синхронизация

1. **Регистрация** — клиент выводит ключи локально (Argon2id → `masterKey` →
   `authKey`/`dataKey`) и загружает SRP-соль/верификатор плюс `dataKey`, обёрнутый мастер-ключом.
   Ничего из загруженного не достаточно для расшифровки.
2. **Вход** — SRP-6a challenge/verify; сервер узнаёт лишь, что клиент знает пароль, но не сам
   пароль. При успехе выдаются короткоживущие access + refresh JWT.
3. **Push/pull** — клиенты загружают (`PUT`) батчи зашифрованных записей; конфликты решает
   last-writer-wins (по `version` записи, затем `deviceId` как тайбрейк). Скачивание — дельты
   по монотонному курсору (`?since=`).
4. **Live-обновления** — WebSocket `/sync` пушит сигнал «есть изменения», несущий только новый
   курсор и никогда — содержимое; клиенты затем забирают дельту.
5. **Удаления** — распространяются как tombstone-записи и физически вычищаются спустя
   `SKERRY_TOMBSTONE_DAYS`.
6. **Новое устройство** — либо логинится и забирает обёрнутый `dataKey` из `/vault/keys`,
   либо использует быстрый QR-паринг (`/pairing/*`, одноразовая сессия с коротким TTL).

Все шифроблобы (`blob`, `wrappedDataKey`, `encryptedDataKey`) передаются как base64.

## API

### Health и аутентификация

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/healthz` | Liveness (открыт; используется healthcheck'ом контейнера). БД не трогает. |
| `GET` | `/readyz` | Readiness: `200` + `{"status":"ready","db":"up"}` либо `503`, если проба БД упала три раза подряд. |
| `GET` | `/metrics` | Prometheus-экспозиция. По умолчанию выключена — см. `SKERRY_METRICS`. |
| `POST` | `/auth/register` | Регистрация: SRP-соль/верификатор + обёрнутый dataKey → токены. |
| `POST` | `/auth/srp/challenge` → `/auth/srp/verify` | Вход по SRP-6a без передачи пароля. |
| `POST` | `/auth/refresh` | Ротация access/refresh токенов. |
| `POST` | `/auth/change-password` | Смена пароля: SRP-доказательство текущего, новый верификатор и перезавёрнутый dataKey. |
| `GET` | `/auth/web-password` (JWT) | Задан ли пароль веб-доступа — это читает карточка «Веб-доступ» в приложении. |
| `POST` | `/auth/web-password` (JWT) | Задать, сменить или снять **пароль веб-доступа** из приложения. Снятие закрывает и открытую сессию браузера. |
| `POST` | `/auth/web-login` | Пароль веб-доступа → обычная пара токенов. Под рейт-лимитом; сессия регистрируется как устройство с `platform = "web"`. |

### Vault и устройства (JWT)

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/vault/keys` | Обёрнутый `dataKey` для нового устройства. |
| `GET` | `/vault/records?since={cursor}` | Дельта зашифрованных записей. |
| `GET` | `/vault/envelopes` | Метаданные записей и короткое превью шифротекста — размеры, но не блобы (раздел «Хранилище» в кабинете). |
| `GET` | `/account/summary` | Собственные счётчики вызывающего: устройства, записи, надгробия, объём шифротекста, последняя синхронизация. |
| `GET` | `/account/activity` | Собственные строки аудита вызывающего (командные строки исключены). |
| `PUT` | `/vault/records` | Batch upsert с LWW (version, затем deviceId). |
| `WS` | `/sync` | Push «есть изменения» (только курсор, без содержимого). |
| `GET` / `DELETE` | `/devices`, `/devices/{id}` | Список устройств и отзыв. |
| `POST` | `/pairing/start` (auth) → `/pairing/claim` | Быстрый локальный QR-паринг. |

### Teams (JWT)

E2E-шифрованный шеринг: командные записи для сервера — шифротекст, членство выдаётся через
sealed-envelope приглашения на публичные ключи участников.

| Метод | Путь | Назначение |
|---|---|---|
| `PUT` | `/account/key` | Публикация публичного ключа аккаунта. |
| `GET` | `/account/keys/{accountId}` | Публичный ключ участника (для envelope). |
| `POST` / `GET` / `DELETE` | `/teams`, `/teams/{id}` | Создать, перечислить, удалить команду. |
| `GET` / `POST` | `/teams/{id}/members` | Список участников; приглашение (sealed envelope). |
| `PUT` | `/teams/{id}/members/{accountId}/role` | Смена роли (owner/member). |
| `DELETE` | `/teams/{id}/members/{accountId}` | Удаление участника / отзыв доступа. |
| `POST` | `/teams/{id}/accept` | Принятие приглашения. |
| `GET` / `PUT` | `/teams/{id}/records` | Pull/push зашифрованных общих записей. |
| `GET` | `/teams/{id}/activity` | Лента активности команды. |

### Admin (под `SKERRY_ADMIN_TOKEN`, заголовок `X-Admin-Token`)

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/admin/health` | Liveness (открыт). |
| `GET` | `/admin/stats` | Агрегаты: аккаунты, устройства, записи, размеры блобов. |
| `GET` | `/admin/devices` | Все устройства: платформа, курсор, последняя синхронизация. |
| `GET` | `/admin/activity` | Аудит-лог (последние 2000 событий). |
| `GET` | `/admin/accounts`, `/admin/accounts/{id}/records` | Список аккаунтов, метаданные записей аккаунта. |
| `DELETE` | `/admin/devices/{id}?accountId=` | Отзыв устройства из консоли. |
| `DELETE` | `/admin/accounts/{id}/tombstones` | Досрочная очистка tombstone-записей аккаунта. |
| `DELETE` | `/admin/accounts/{id}` | Удаление аккаунта со всеми данными. |

### Удаление аккаунта

`DELETE /admin/accounts/{id}` в одной транзакции убирает всё, что принадлежит аккаунту: записи,
устройства, пейринг-сессии, опубликованные ключи Teams, членства и гранты областей — не остаётся ни
одной строки, ссылающейся на исчезнувший id (SQLite внешние ключи не проверяет, PostgreSQL просто не
даст удалить аккаунт, пока такие строки есть). Команда, которой аккаунт **владел**, переходит к
старшему активному участнику; если активных участников не осталось, команда удаляется вместе со
своими записями, областями и грантами. В аудит-строке перечислены затронутые команды и их новые
владельцы, в журнал самой команды пишется `team.owner_replaced`, а всем оставшимся участникам
уходит тот же live-сигнал о членстве, что и при любом другом изменении состава. Оставшимся стоит
ротировать ключ команды — удалённый аккаунт его знал, а ротацию делает только клиент.

Зарегистрировать тот же id заново это не мешает: id детерминирован, локальный волт остался на
устройстве, и повторная регистрация зальёт его обратно. Если это важно — закройте инстанс
(`SKERRY_REGISTRATION=closed`).

## Веб-интерфейс

Один статический бандл, три входа, раздаёт сам Ktor:

| URL | Кто | Ключ | Что показывает |
|---|---|---|---|
| `/` | любой | нет | Работает ли инстанс, версия, открыта ли регистрация, адрес для вставки в клиент. |
| `/account` | владелец аккаунта | id аккаунта + **пароль веб-доступа** (задаётся в приложении) | Устройства, команды, живые сессии, конверты хранимых записей, журнал аккаунта, разделы безопасности. |
| `/console` | оператор | `SKERRY_ADMIN_TOKEN` | Счётчики инстанса, аккаунты (устройства и конверты записей раскрываются внутри строки), наблюдаемость, аудит. |

Пароль веб-доступа — отдельный ключ, не связанный с ключами хранилища: страницу, которая его
спрашивает, отдаёт тот же сервер, который он защищает, поэтому мастер-пароль так не ходит. Браузер,
вошедший по нему, читает метаданные, которые сервер и так хранит открытыми, и не может расшифровать
запись — `dataKey` в этом потоке не участвует. Полученный токен ограничен на стороне сервера ровно
этим: только чтение, без `/vault/keys` и `/vault/records`, плюс отзыв устройства. Потеря пароля
стоит сброса из приложения; мастер-пароль по-прежнему невосстановим by design.

Пароль задаётся в приложении: **Настройки → Синхронизация → Веб-доступ**, на подключённом
устройстве. Там же он меняется и снимается — снятие закрывает и открытую в этот момент сессию
браузера. Длина 8–256 символов. За карточкой стоит `POST /auth/web-password`
(`{"password": null}` снимает пароль), тот же вызов подойдёт и скрипту.

Пара токенов аккаунта живёт в `sessionStorage` и умирает вместе со вкладкой; admin-токен хранится
только в памяти и после перезагрузки спрашивается заново. Любое разрушающее действие (отзыв,
очистка, удаление) сначала называет свой радиус поражения.

Членством и ключами команд из браузера управлять **нельзя**: приглашение и ротация ключа запечатывают
конверт ключом команды, которого у веб-сессии нет. Разделы команд — только на чтение.

Языки интерфейса: английский, русский, китайский (`?lang=` в URL, затем сохранённый выбор, затем
язык браузера). Zero-knowledge сохраняется везде: в списках — идентификаторы, типы, размеры и время,
а превью шифротекста показано ровно тем, чем оно является.

> Шрифты (Space Grotesk, JetBrains Mono) зашиты в сервер (`resources/web/assets/fonts/*.woff2`),
> иконки — инлайн-SVG, CSP — `default-src 'self'`. Страницы полностью работают офлайн, без
> обращений к внешним CDN. Китайский отдаётся системному CJK-стеку — зашитые начертания покрывают
> только latin и latin-ext.

> ⚠️ Метаданные содержат `accountId` (это e-mail) и удерживаются в аудит-логе (последние 2000
> событий). Для single-user self-host оператор и есть субъект данных — приемлемо. Admin-токен
> ходит в заголовке `X-Admin-Token` открытым текстом: обязательно поставьте TLS-терминатор
> (ниже), иначе токен виден в сети.

## Админ-CLI

`skerry-admin` лежит в том же образе, что и сервер (`/app/bin/skerry-admin`), и работает через те же
эндпоинты `/admin`, что и консоль оператора — одна реализация и один гейт авторизации на каждую операцию.
Нужен `SKERRY_ADMIN_TOKEN` и доступный сервер; напрямую в БД CLI не лазит.

```bash
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin stats
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin devices list --account alice@example.com
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin devices revoke devA --account alice@example.com
docker exec -e SKERRY_ADMIN_TOKEN=… skerry-sync skerry-admin accounts delete alice@example.com --yes
```

| Команда | Назначение |
|---|---|
| `health` | Живость и версия (токен не нужен). |
| `stats` | Итоги по инстансу: аккаунты, активные устройства, записи, объём. |
| `accounts list` / `accounts records <id>` | Аккаунты с агрегатами; метаданные записей одного аккаунта. |
| `accounts purge-tombstones <id>` | Убрать маркеры удаления, которые все устройства уже синхронизировали. |
| `accounts delete <id> --yes` | Удалить аккаунт со всеми данными (необратимо — потому и флаг). |
| `devices list [--account id]` | Активные устройства, свежие сверху. |
| `devices revoke <id> --account <id>` | Отозвать устройство (позже оно может заново аутентифицироваться). |
| `activity` | Последние события аудит-лога. |
| `metrics` | Сырая Prometheus-экспозиция (берёт `SKERRY_METRICS_TOKEN`). |

Общие опции: `--url` (по умолчанию `SKERRY_ADMIN_URL`, иначе `http://127.0.0.1:$SKERRY_PORT`),
`--token` / `--token-file` (флаг видно в `ps` — лучше переменная окружения или файл-секрет),
`--limit`, `--json` (печатает JSON сервера как есть, под `jq`), `--help`.

Коды выхода рассчитаны на скрипты: `0` ок, `1` ошибка, `2` неверный вызов, `3` не авторизован,
`4` не найдено, `5` сервер недоступен. Против удалённого инстанса — `--url https://sync.example.com`;
URL должен указывать на корень сервера (путь-префикс за реверс-прокси не поддерживается).

## Метрики и мониторинг

`/metrics` отдаёт Prometheus-экспозицию — по умолчанию выключено: на zero-knowledge сервере
метаданные и есть поверхность атаки. Включение с токеном:

```bash
-e SKERRY_METRICS=token -e SKERRY_METRICS_TOKEN="$(openssl rand -hex 24)"
```

```yaml
# prometheus.yml
scrape_configs:
  - job_name: skerry-sync
    static_configs: [{ targets: ["sync.example.com:8080"] }]
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/skerry-metrics-token
```

Что есть помимо штатных семейств `jvm_*`, `process_*` и `hikaricp_*`:

| Семейство | На какой вопрос отвечает |
|---|---|
| `skerry_http_server_requests_seconds` | Латентность и объём по `method`, `route` (**шаблон**, никогда не id), `status`. |
| `skerry_http_rejected_requests_total`, `skerry_http_unhandled_exceptions_total` | Запросы, отбитые до маршрутизации (411/413); ошибки на стороне сервера. |
| `skerry_auth_attempts_total{kind,outcome}`, `skerry_auth_tokens_issued_total` | Исходы логина/регистрации/refresh — сигнал брутфорса. |
| `skerry_admin_auth_failures_total`, `skerry_metrics_auth_failures_total` | Неверные статические токены, т.е. кто-то щупает консоль или скрейп-эндпоинт. |
| `skerry_auth_jwt_rejected_total{reason}`, `skerry_team_authz_denied_total{reason}` | Отбитые токены устройств; отказы доступа к команде/области. |
| `skerry_sync_records_received_total` / `_pulled_total` / `skerry_sync_push_bytes_total` | Объём синхронизации по областям (`account`, `team`). |
| `skerry_sync_ws_sessions`, `…_opened_total`, `…_closed_total{reason}`, `…_session_duration_seconds` | Сокеты live-push: сколько открыто, почему закрываются, сколько живут. |
| `skerry_accounts`, `skerry_devices{state}`, `skerry_records{state}`, `skerry_storage_bytes{scope}`, `skerry_db_file_bytes`, `skerry_teams`, `skerry_pairing_sessions{state}` | Инвентарь, обновляется в фоне (см. `SKERRY_METRICS_INVENTORY_SECONDS`). |
| `skerry_inventory_last_success_time_seconds`, `skerry_inventory_errors_total` | Не устарел ли инвентарь выше. |
| `skerry_db_up`, `skerry_db_probe_duration_seconds` | Проба БД, стоящая за `/readyz`. |
| `skerry_build_info{version}`, `skerry_server_start_time_seconds` | Запущенная версия; детект рестартов. |

**Ни в одном лейбле нет accountId, deviceId, recordId, teamId, scopeId или IP-адреса.** Это значения,
которые выбирает клиент: как лейблы они позволили бы пользователю раздувать реестр до падения
процесса и опубликовали бы ровно те метаданные, которые архитектура держит вне доступа сервера.
Цифры по конкретным аккаунтам живут за админ-токеном, в `/admin/accounts`.

Набор алертов для начала:

```promql
skerry_db_up == 0                                                    # БД недоступна
time() - skerry_inventory_last_success_time_seconds > 300            # инвентарные gauge устарели
changes(skerry_server_start_time_seconds[15m]) > 2                   # рестарт-цикл
rate(skerry_admin_auth_failures_total[10m]) > 0                      # подбирают админ-токен
sum(rate(skerry_auth_attempts_total{outcome="denied"}[10m])) > 0.2   # брутфорс логина
hikaricp_connections_pending > 0                                     # конкуренция за единственный коннект SQLite
predict_linear(skerry_db_file_bytes[6h], 7*24*3600) > 20e9           # том забьётся на этой неделе
skerry_records{state="tombstone"} / skerry_records{state="live"} > 0.5  # чистка тумбстоунов не работает
```

## Безопасность в проде

- Задайте устойчивый `SKERRY_JWT_SECRET` (иначе рестарт инвалидирует все токены) и непустой
  `SKERRY_ADMIN_TOKEN`.
- Бэкап = файл SQLite (`/data`) или дамп PostgreSQL; данные зашифрованы, но это ваша
  единственная точка восстановления.
- Сам сервер слушает cleartext HTTP — TLS терминируется обратным прокси (ниже). Полезная
  нагрузка и так E2E-зашифрована (zero-knowledge), SRP безопасен поверх cleartext, но
  **админ-токен и метаданные (включая `accountId` = e-mail) идут открыто** — без TLS они
  видны в сети. Для публично доступного хоста TLS обязателен.

### TLS-терминатор

Клиент указывает `https://…` — WebSocket `/sync` автоматически переключается на `wss://`
(тот же хост).

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
        # WebSocket /sync (live pull): realtime notifications break without these two headers.
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h; # /sync is a long-lived connection; don't cut it on timeout
    }
}
```

Привяжите сервер к loopback (`SKERRY_HOST=127.0.0.1`), чтобы 8080 не торчал в сеть в обход
прокси.

> **Self-host в локальной сети без TLS** — допустимый осознанный выбор: трафик E2E-зашифрован,
> метаданные остаются в доверенной LAN. Android-клиент разрешает cleartext
> (`network_security_config.xml`). Как только хост доступен извне — ставьте TLS.

## Тесты

```bash
./gradlew :server:test
```

Покрывают LWW-конфликты, SRP-роундтрип, JWT, роли/ACL команд и полный HTTP-флоу
(register → вход → push/pull → devices → pairing → admin).
