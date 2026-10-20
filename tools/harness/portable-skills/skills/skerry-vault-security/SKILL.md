---
name: skerry-vault-security
description: "Work on Skerry encrypted vault, credentials, biometrics, trusted devices, host identity, secret storage or hostile external data. Use for concrete security-sensitive implementation or review."
---

# Skerry: vault, секреты и границы доверия

## Модель

Vault local-first; payload шифруется на клиенте. Основные файлы shared commonMain/vault: Vault/VaultRecord/VaultCrypto, FileVault/VaultFileCodec, IonspinVaultCrypto, VaultRecordCodec/IndexedVaultRecords. Crypto — libsodium Argon2id + XChaCha20-Poly1305. Сервер хранит ciphertext и sync metadata, не master key. Из этого не следует, что metadata не требуют аутентификации и проверки.

До изменения читать код record AAD/version/merge и тесты, а не воспроизводить формат по этому тексту. Начать с каталога `docs/coding-guidelines.md`. Не менять KDF/encryption/file format/identity semantics «для ускорения» без отдельного обоснованного контракта и migration strategy.

## Persistence

- Secret-bearing files только atomicWriteUtf8/проектный atomic+0600 path. Desktop config dir PrivateConfig.ensureDir — 0700. Plain writeText не эквивалент atomic replacement и permissions.
- Vault read-modify-write только vault.transaction. Сохранять merge/LWW/tombstone semantics и unknown future record types. Упорядоченный список — WorkspaceLayout/LibraryOrder record, не порядок records().
- Vault-backed JSON collection через VaultRecordCodec. Декодированный id должен соответствовать authenticated record id до превращения в lazy list key или index. Недоверенный remote/team payload может подменить id/duplicate key.
- Проверять size/count/length до allocation/decoding/side effects. Ошибка одного remote authenticated envelope не должна молча уничтожать локальный record.
- Intermediate secret copies обнулять там, где API это допускает; сравнение через existing constantTimeEquals. Не обещать полное memory erasure JVM immutable String.

## Credentials и доступ к секретам

CredentialStore, credential usage, key resolver/inspector, biometric support, device keyring — разные обязанности. VaultGateController управляет gate/unlock, SecretCopyAuthorizer — authorization copy; SecretClipboard имеет sensitivity/clear timer. Нельзя заменить её обычным SystemClipboard, даже если так удобнее Wayland paste.

BiometricKeyStore/AndroidBiometricKeyStore и trusted-device secret store завязаны на OS protection. Copy старого config directory на новый ПК не гарантирует unlock. При переносе dev environment создавать новые dev artifacts; production migration — отдельная пользовательская задача с backup/recovery plan, не часть установки скиллов.

Lock teardown должен очищать/останавливать принадлежащие unlocked state операции и UI caches по контракту. Auto-lock/background/session keepalive могут взаимодействовать: проверить actual policy, не закрывать все remote sessions без проверки intended behavior и не оставлять секреты видимыми после lock.

## Host identity

SSH known hosts и trusted CA — VaultKnownHostsStore/VaultTrustedCaStore; mismatch provenance хранится отдельно по существующему store. RDP certificate TOFU — RdpCertificateVerifier с общим HostTrust prompt. Новое/изменившееся доверие, отмена и отказ — разные outcomes. Не отключать проверки сертификата/ключа ради соединения с dev host.

HostTrustRequest/Prompt/Decider и BlockingHostTrust bridge связывают sync handshake с suspend UI prompt. Concurrent asks идут через PromptQueue; ответ нельзя применять к другому host/request. Логи должны давать evidence без приватных ключей/паролей/токенов.

## Untrusted network и UI

HTTP к sync/AI/model/release host — untrustedHttpClient + per-caller ResponseSizeLimit. Bounded header section/whole-call timeout нужны даже на HTTPS. WebSocket — untrustedWebSocket/WebSocketLimits с frame cap, queue bound, handshake deadline, incoming read/Close. Не заменять bare HttpClient(CIO)/Ktor websocket plugin.

UI имена/notes/commands/clipboard/server failure text — untrusted. Использовать untrustedLabel/sanitizeServerText/sanitizeServerHost/CommandQuote. Controls/bidi/oversized strings не должны подменять смысл confirmation или заставлять выделять unlimited memory. Фильтрация display не заменяет protocol/domain validation.

## Приёмка security-sensitive изменения

Threat boundary сформулирована: кто контролирует bytes, какие resources/secrets/actions доступны и что считается отказом. Regression проверяет конкретное нарушение и безопасный recovery, а не только «не бросает exception». Добавить relevant malformed input, tampered metadata, duplicate id, race/cancel, permission/atomicity tests. Для new format — compatibility/rollback/recovery по scope.

Запустить выбранные gates и security reviewer отдельно от Gradle. Findings проверять и исправлять/отклонять с evidence; не добавлять generic warning list вместо устранения дефекта. Итог разделяет local unit/crypto/integration proof и непроверенные реальные OS keystore/biometrics/серверные среды. Credentials, production vault и raw private logs никогда не включать в portable bundle.
