# Skerry Sync — проектирование

> Self-hosted E2E-синхронизация (модель Vaultwarden): сервер хранит только шифроблобы,
> мастер-пароль никогда не покидает устройство. Сервер — `server/` (Ktor, AGPL-3.0),
> клиентская часть — `shared/sync/` (KMP, общая для всех платформ).

## 1. Иерархия ключей (zero-knowledge)

```
master password ──┐
                  ├─ Argon2id (m=64MiB, t=3, p=1, salt = accountId)
email/accountId ──┘
        │
        ▼
   masterKey (256 bit, только в памяти устройства)
        │
        ├─ HKDF(masterKey, "skerry-auth") ──► authKey ──► SRP-верификатор на сервере
        │                                     (доказательство входа; пароль не передаётся)
        │
        └─ расшифровывает wrappedDataKey ◄── сервер хранит только обёртку
                  │
                  ▼
             dataKey (256 bit, случайный, создаётся при регистрации vault)
                  │
                  └─ XChaCha20-Poly1305 ──► шифрование каждой записи vault
```

Принципы:
- **Сервер видит**: accountId, SRP-верификатор, wrappedDataKey, шифроблобы записей, метаданные синхронизации (id, версия, timestamp). **Сервер не видит**: пароль, masterKey, dataKey, содержимое записей, имена хостов.
- Смена мастер-пароля = переобёртка dataKey (записи не перешифровываются).
- Аппаратные ключи (Secure Enclave, YubiKey) **не синхронизируются** — регистрируются на каждом устройстве отдельно; в vault хранится только их публичная половина и метаданные.

## 2. Модель данных

Запись vault (`VaultRecord`):

| Поле | Тип | Видимость серверу | Описание |
|---|---|---|---|
| `id` | UUID | да | идентификатор записи |
| `type` | enum | да | `host` / `group` / `identity` / `known_host` / `snippet` |
| `version` | int64 | да | Lamport-счётчик для LWW |
| `updatedAt` | ISO-8601 UTC | да | для отображения, не для разрешения конфликтов |
| `deviceId` | UUID | да | кто сделал последнее изменение |
| `deleted` | bool | да | tombstone (хранится 90 дней) |
| `blob` | bytes | **нет (шифротекст)** | XChaCha20-Poly1305(dataKey, payload), nonce в префиксе |

Payload внутри `blob` (пример для `type=host`, синтетические значения):

```json
{
  "name": "prod-web-01",
  "address": "192.168.1.45",
  "port": 22,
  "username": "root",
  "groupId": "f3a9…",
  "identityId": "7c44…",
  "aiPolicy": "strict",
  "tags": ["production"]
}
```

## 3. Протокол синхронизации

- **Транспорт**: REST + WebSocket (realtime push), TLS обязателен.
- **Разрешение конфликтов**: last-write-wins по `version` (Lamport). При равенстве — лексикографически больший `deviceId`. Конфликт уровня записи, не поля: записи мелкие, потеря «проигравшего» изменения приемлема и показывается в журнале.
- **Дельта-синхронизация**: клиент хранит `lastSyncVersion`, запрашивает `GET /vault/records?since=`.

### API

| Метод | Путь | Назначение |
|---|---|---|
| `POST` | `/auth/register` | создать аккаунт: SRP-верификатор + wrappedDataKey |
| `POST` | `/auth/srp/challenge` → `/auth/srp/verify` | вход без передачи пароля, выдаёт accessToken |
| `GET` | `/vault/keys` | wrappedDataKey для нового устройства |
| `GET` | `/vault/records?since={version}` | дельта записей |
| `PUT` | `/vault/records` | batch upsert (id, version, blob) |
| `WS` | `/sync` | push «появились изменения» (без содержимого) |
| `GET/DELETE` | `/devices`, `/devices/{id}` | список и отзыв устройств |
| `GET` | `/admin/health`, `/admin/stats` | для админ-консоли (отдельная admin-роль) |

### Паринг нового устройства

Вариант A (стандартный): вход мастер-паролем → SRP → получение wrappedDataKey → локальная расшифровка.
Вариант B (быстрый, локальный): существующее устройство показывает QR = `{serverUrl, accountId, transferKey}`; dataKey передаётся новому устройству зашифрованным одноразовым transferKey через сервер (сервер видит только шифротекст, TTL 5 минут).

## 4. Модель угроз (кратко)

| Угроза | Защита |
|---|---|
| Компрометация сервера / дампа БД | данные — шифротекст; пароль не подбирается офлайн без Argon2id-стоимости |
| MITM | TLS + certificate pinning опционально в клиенте |
| Кража accessToken | токен даёт доступ только к шифроблобам; короткий TTL + refresh |
| Злонамеренный админ сервера | не может читать данные; может удалить/откатить — клиент детектирует откат по монотонности `version` |
| Утеря мастер-пароля | данные невосстановимы; recovery kit (распечатка dataKey) — ответственность пользователя |

## 5. Реализация

- Сервер: Ktor + PostgreSQL/SQLite, образ Docker, конфиг одним `.env`. AGPL-3.0.
- Клиент: `shared/sync/` — общий KMP-код; крипто через libsodium-биндинги (Android/iOS/JVM).
- Админ-консоль: статические страницы, отдаваемые тем же Ktor (прототип — `docs/skerry-sync-prototype.html`).
- Фаза 2 по плану продукта; интерфейсы (`SyncClient`, `VaultCrypto`) закладываются в ядро в Фазе 1.
