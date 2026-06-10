package app.skerry.shared.vault

/**
 * Локальное зашифрованное хранилище хостов/ключей/identity.
 * Иерархия ключей и формат записей — `docs/skerry-sync-design.md`
 * (Argon2id → masterKey → authKey/dataKey, XChaCha20-Poly1305, VaultRecord).
 */
interface Vault
