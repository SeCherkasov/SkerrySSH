---
name: skerry-server
description: "Develop and verify the Skerry Ktor sync server, SQL repositories, authorization, web/admin surfaces, metrics and local container runtime. Use for server-side behavior or server-only setup."
---

# Skerry: сервер, API и база данных

## Устройство

Application.kt строит ServerConfig, guardConfig, Db, Services, plugins/routes и background cleanup/observers. ServerConfig читает env; Plugins задаёт общие guards/serialization/rate limits. Auth: SRP service, token service, web password hashing. Routes: auth/accounts/devices/pairing/vault/team/scopes/share/admin/metrics/web. DB repositories отделяют queries/transactions от route-level HTTP decisions. Wire DTO — :sync-wire.

Перед SQL/route change читать соответствующие repository/validator/auth callers/tests; `RoutesTestSupport` — существующая test abstraction, не копировать token guards по всем файлам.

## Локальный запуск

JDK 21, Python harness; для server-only graph Android SDK не нужен:

```sh
# Из checkout; выполнить через bounded_run.py. Только локальный dev режим.
SKERRY_HOST=127.0.0.1 SKERRY_DEV=1 ./gradlew :server:run -PserverOnly
```

Не выполнять этот bare command вне resource wrapper в среде пользователя. Запуск dev server — только при необходимости задачи; в общем onboarding достаточно описания/диагностики. Не использовать production SQLite и default wildcard bind. Конфигурация читается из окружения: наличие .env-файла само по себе не означает, что Gradle его импортировал.

Основные переменные (точные defaults сверять в ServerConfig): HOST/PORT, DB_URL/DB_USER/DB_PASSWORD, JWT_SECRET/JWT_ISSUER, ADMIN_TOKEN, ACCESS_TTL/REFRESH_TTL/PAIRING_TTL, TOMBSTONE_DAYS, CORS_HOSTS/TRUSTED_PROXIES, MAX_BODY_BYTES/MAX_ACCOUNT_BYTES/MAX_SCOPE_BYTES, REGISTRATION/MAX_ACCOUNTS, METRICS/METRICS_TOKEN/INVENTORY_SECONDS — все с префиксом SKERRY_.

Server отказывает при известном insecure JWT default без SKERRY_DEV=1; metrics token-mode без token тоже отказ. Production secret должен быть сильным и стабильным через перезапуск: rotation инвалидирует выданные токены. Не печатать значения в журналах и не включать их в bundle.

## Storage и migrations

SQLite по умолчанию, PostgreSQL по JDBC URL, Exposed + Hikari. Db.connect выбирает pool; snapshot SQLite pool=1, PostgreSQL=10. Background metrics inventory/DB probe вынесены из scrape path, чтобы не держать single SQLite connection на каждом запросе метрик.

Database.createSchema создаёт недостающие tables/columns и учитывает допустимые SQLite statements; это не универсальная versioned migration система. Читать фактический schema code, Tables/Models и тесты. Для нового столбца определить default/nullability/backfill/compatibility, SQLite и PostgreSQL semantics, atomicity. Не вводить destructive migration по одному green empty-database test. Проверить existing populated DB upgrade в изолированной копии, rollback/backup по scope.

Encrypted blobs и quota accounting должны соответствовать storage boundary. Quota на growth ≠ запрет любой правки после превышения. Cursor/version bounds и paging должны избегать пропусков/overflow. Authenticated user/device/team/scope binding проверять в transaction там, где есть race.

## HTTP, auth и websocket

Length/format validation до DB query, one-shot consume и allocation. HTTP 400/401/403/404/409/413 различать по контракту; не возвращать internal exception message/секреты. Rate-limit key/proxy trust нельзя строить по произвольному client header без trusted-proxy policy.

JWT device revocation/refresh/session guards должны работать после удаления/отзыва, не только до expiration. Admin token, account web session и sync JWT — разные trust boundaries. Empty ADMIN_TOKEN закрывает admin data endpoints. CORS — browser policy, не auth для native clients.

WebSocket: auth/handshake bound, incoming Close read, bounded frame/queue, disconnect cleanup, slow consumer. ChangeNotifier/ShareRelay не должны копить unlimited данные или оставлять orphan subscribers.

## Web/CLI/metrics

Статический frontend — `server/src/main/resources/web` (index.html, assets JS/CSS), route guard — WebFrontendRoutes/WebSessionGuard. Изменения этих executable assets не являются docs-only. Проверять browser state/session/logout/errors и отсутствие leaks, а не только HTML syntax.

Admin CLI в server/cli, установлен как skerry-admin. Он вызывает API, не читает DB напрямую. Для локального изучения начать с `--help` и разрешённых read-only команд; accounts delete/devices revoke являются внешними изменениями и не нужны для обычного setup.

Metrics off/token/open — читать actual config. `/healthz` — liveness, readiness/probe — отдельная семантика. Успешный liveness не доказывает доступность DB/правильность sync. Не открывать admin/metrics наружу ради smoke.

## Docker

Build context — **корень репозитория**: Dockerfile `server/Dockerfile`, serverOnly installDist, JDK build/JRE runtime, unprivileged uid/gid 999, /data volume. Bind mount должен быть доступен этому user; не чинить SQLITE_READONLY chmod 777.

Сборка Docker содержит Gradle внутри контейнера: она тоже должна соблюдать общий resource contract, а docker daemon не обязательно входит в outer user cgroup. Задавать/проверять ограничения builder/container явно; не считать bounded wrapper вокруг docker CLI достаточным. Deployment/push image только по прямой задаче. Client tags v* и server-v* имеют отдельный lifecycle.

## Проверки

Server test suite + shared desktop integration на полном client graph по плану. Только -PserverOnly не проверяет shared integration. Route tests: validation/auth/revocation/ACL/log redaction/quotas/paging/WS close. Repo tests: SQLite baseline, upgrade, concurrent transaction; PostgreSQL live отдельно, если реально проверен.

Harness финально выбирает server и consumers; Kotlin/security reviews обязательны по scope. Указать непроверенные PostgreSQL/real TLS proxy/container arch/browser окружения. Контейнер healthy — startup proof, не end-to-end two-device sync.
