---
name: skerry-sync-teams
description: "Work on Skerry encrypted delta sync, account links, pairing, team scope keys/ACLs or encrypted session sharing. Use for consistency, identity, recovery and compatibility changes."
---

# Skerry: синхронизация и команды

## Слои и контракты

- `sync-wire`: SyncWireDto, AccountWireDto, TeamWireDto, ShareWireDto — общий wire, не зеркалить модели вручную.
- shared sync: SyncClient/SyncSession, SyncEngine, SyncSettings/DeviceLocalRecords, SyncStateStore/KeyedStateStore. jvmShared KtorSyncClient/FileSyncStateStore, bounded HTTP/WS.
- composeApp ui/sync: SyncCoordinator, SyncContracts (ServerLink/TeamLink/ShareLink), sealed token/config stores, ServerHealthMonitor, failure-to-localized-text, pairing/form state.
- shared team: TeamClient, TeamScope/TeamScopeRef, TeamVaults/TeamStores, peer keys/pin notices/invite/share; UI TeamsCoordinator.
- shared share: SessionShareCodec/Frame, ShareChannel, SessionShareHost/SharedSessionViewer. server share/ShareRelay — relay отдельно от vault sync.

## Account sync

SyncEngine делает pull delta → authenticated merge → push local records → pull again, чтобы cursor не пропускал concurrent changes. Это описание snapshot, перед изменением читать actual algorithm, paging/version boundary и tests.

Cursor — позиция в истории **одного server link**. Account id может совпадать на разных серверах. ServerLink.cursorKey включает нужную identity; team space — link + space через KeyedStateStore. Нельзя упростить ключ до accountId/teamId. Forget/rejoin должен очищать соответствующие persisted cursors по всем применимым links, иначе новая сессия пропустит данные.

LWW merge, authenticated metadata, unknown future types, tombstones и per-type sync settings сохранять. DeviceLocalRecords фильтрует записи по payload/локальному статусу независимо от toggle: settings не могут разрешить push device-bound secrets. После settings merge проверять изменение filter в том же цикле по текущему контракту.

Более новые record types могут идти отдельным compatibility batch; только конкретно предусмотренные old-server refusal codes допускают downgrade. Network/auth/quota/500 нельзя молча превратить в «старый сервер не умеет». SyncOutcome.changedTypes помогает reload только изменившихся catalogs; rejected authenticated envelopes должны давать observable signal, не терять локальные данные.

## Coordinator lifecycle и recovery

SyncCoordinator инжектирует engine/client/stores. Связать network job, authenticated session, vault unlock state и server link одной identity. При switch/unlink/lock/cancel старые callbacks не публикуют состояние новой link. Refresh tokens, reconnect/retry/health monitoring и reconcile debt имеют разные обязанности.

Повторить: failure → recovery → success, cancel во время linking, concurrent connect, lock/unlock, token revoked, server unavailable, reactivation. Retry/backoff — не fallback business operation. UI должен различать «нет изменений», «не смог синхронизировать», «данные отклонены», «server capability не поддержан».

## Pairing и web access

Pairing payload/code/QR untrusted. Length/format/binding валидировать до расходования one-shot code или server side effect. Учитывать expiry, replay, link/account/device binding, cancel/timeout и два параллельных pairing запроса. QR camera optional; текстовый путь — реальный альтернативный ввод, его тоже проверять.

Web access password projection — отдельный доступ сессии браузера, не передача master password в admin console. Читать WebAccessClient, server WebSessionGuard, соответствующие routes и tests. Изменение account password может затрагивать SRP verifier, vault wrapping, device sessions и web session lifecycle; не ограничиваться сменой одного form field.

## Teams

Scope/team identifiers и peer-provided labels не доверенные пути и не доказательство членства. ACL проверяется сервером для каждой операции, ключи/peer identity pinning — клиентом по contract. TeamVaults открывает отдельные vaults по scope, key mismatch/reset классифицируется явно. Не silently recreate vault при любой ошибке чтения.

Revoke/member removal/key rotation: отдельно проверить future access, outstanding sessions, cached peers/invites, unrotated keys и необходимость повторного share. Не утверждать, что уже скачанные секреты можно «отозвать» только ACL. Team activity/display names bounded/sanitized, role/scope tests проверяют cross-team access.

## Live session sharing

Encrypted ShareFrame/SessionShareCodec и relay — отдельный поток, не account record sync. Проверить direction, authentication/frame size/sequence, view/control capability, host/viewer disconnect и key/session identity. Relay websocket должен читать Close; bounded queue нужен против медленного receiver и hostile sender. Не путать terminal recording upload с live share.

## Проверки и приёмка

Common: merge/cursor/device-local/codec/crypto properties. JVM integration: настоящий server roundtrip, malformed HTTP/WS, token refresh/revocation. Compose coordinator: смена identity, concurrent/cancel, pause/lock, reconcile/rejected record outcomes. Server ACL: role/scope/cross-account, quotas/paging/validation.

Live сценарий при соответствующем scope: два dev устройства → create/edit/delete → offline concurrent change → reconnect → switch server → relink; team invite/share/revoke/rotation; live share close/reconnect. Записать client/server versions и проверить mixed-version compatibility. In-process server test не доказывает настоящий reverse proxy, физические два устройства или production DB.
