# Skerry — биометрическая разблокировка vault (проектный документ)

Статус: **одобрено, в реализации** (2026-06-12). Решения по §8 зафиксированы пользователем:
(1) расширяем контракт `Vault` методом `unlockWithDataKey` (+ симметричный `exportDataKey` для
включения); (2) обёртка хранится в отдельном файле `vault.bio` рядом с `vault.json`; (3) auto-lock
вводится в этой же итерации. Уточнение к §3: контракт `Vault` **синхронный** (Argon2id внутри),
поэтому `unlockWithDataKey` синхронный, без `suspend` — async только у биометрического промпта
платформы (`BiometricKeyStore`), его нельзя звать под `synchronized`-локой vault.

Связанные документы: `skerry-sync-design.md` (иерархия ключей), `skerry-product-brief.md`
(MVP: «мастер-пароль + биометрия»). Контракты vault: `shared/.../vault/{Vault,VaultCrypto}.kt`.

## 1. Принцип (что биометрия НЕ делает)

Биометрия **не заменяет** мастер-пароль и **не хранит** его в открытом виде. Zero-knowledge
сохраняется: мастер-пароль не покидает устройство и не лежит на диске. Биометрия — это лишь
**удобный второй путь** довести до памяти тот же `dataKey`, который обычно получается из
`Argon2id(мастер-пароль) → masterKey → unwrap(dataKey)`.

Ключевая идея: при включении биометрии мы кладём в **аппаратное хранилище секретов платформы**
(Android Keystore / iOS Keychain, доступ к ключу огорожен биометрией) обёртку `dataKey` под
**отдельным, не выводимым из пароля ключом** (`bioKey`), который генерится и живёт внутри
secure-enclave/TEE и наружу не извлекается. Разблокировка биометрией = платформа после успешной
проверки отпечатка/лица отдаёт доступ к `bioKey`, которым мы разворачиваем `dataKey`.

Мастер-пароль остаётся **обязательным fallback** (биометрия может быть недоступна, сброшена при
смене отпечатков, или железо скомпрометировано) и **единственным** способом после холодного старта,
пока пользователь явно не включил биометрию.

## 2. Иерархия ключей (надстройка над текущей)

Сейчас (`FileVault`):

```
мастер-пароль ──Argon2id(salt)──▶ masterKey ──unwrap──▶ dataKey ──(в памяти)──▶ seal/open записей
                                   (в файле: salt, wrappedDataKey = wrap_masterKey(dataKey))
```

С биометрией добавляется **вторая обёртка того же dataKey**:

```
bioKey (в Keystore/Keychain, биометрия-gated, не извлекаем)
   └─ wrap_bioKey(dataKey)  ── хранится рядом с vault (или в самом Keystore как зашифрованный blob)
```

`dataKey` один и тот же — записи перешифровывать не нужно. Файл vault не меняется; добавляется
**отдельный артефакт** `vault.bio` (или поле в Keychain) с `wrap_bioKey(dataKey)` + метаданными
(версия, идентификатор bioKey, deviceId).

Важно: оборачиваем **dataKey**, а не masterKey и не пароль. Тогда смена мастер-пароля
(`changePassword` лишь переоборачивает dataKey под новым паролем) **не требует** трогать
биометрическую обёртку — `dataKey` неизменен. Это чистая развязка.

## 3. Платформенные примитивы (за общим контрактом)

Общий контракт в `commonMain` (по architecture-discipline — платформенные либы за интерфейсом):

```kotlin
// shared/commonMain/.../vault/Biometric.kt   (эскиз, НЕ финал)
interface BiometricKeyStore {
    /** Доступна ли биометрия и есть ли зачисленные данные (отпечаток/лицо). */
    fun availability(): BiometricAvailability   // Available / NoHardware / NotEnrolled / LockedOut

    /** Создать неизвлекаемый bioKey в secure storage под алиасом; идемпотентно. */
    suspend fun ensureKey(alias: String): Result<Unit>

    /** Огороженная биометрией операция: показать prompt и, при успехе, обернуть dataKey. */
    suspend fun wrap(alias: String, plaintext: ByteArray, prompt: BiometricPrompt): Result<ByteArray>

    /** Огороженная биометрией операция: показать prompt и развернуть обёртку. */
    suspend fun unwrap(alias: String, wrapped: ByteArray, prompt: BiometricPrompt): Result<ByteArray>

    /** Удалить bioKey (выключение биометрии / паника / смена устройства). */
    fun deleteKey(alias: String)
}
```

Реализации (`expect`/`actual` или DI-инъекция, как `VaultCrypto`):

| Платформа | API | Гарантии |
|-----------|-----|----------|
| Android | `KeyStore("AndroidKeyStore")` + `KeyGenParameterSpec(setUserAuthenticationRequired(true), setUnlockedDeviceRequired(true))` + `BiometricPrompt` (`androidx.biometric`) с `CryptoObject(Cipher)` | Ключ в TEE/StrongBox, недоступен без живой биометрии; `setInvalidatedByBiometricEnrollment(true)` — ключ инвалидируется при добавлении нового отпечатка |
| iOS/iPadOS | Keychain item с `SecAccessControl(.biometryCurrentSet, .privateKeyUsage)` + `LAContext` (LocalAuthentication); ключ Secure Enclave (`kSecAttrTokenIDSecureEnclave`) | Ключ в Secure Enclave, `biometryCurrentSet` инвалидирует при смене набора биометрии |
| Desktop (Linux/Win/macOS) | **Нет единого биометрического API.** MVP: `availability() = NoHardware`, биометрия скрыта в UI. (Позже: macOS LocalAuthentication через JNI; Windows Hello; Linux — polkit/fprintd опционально) | — |

Desktop остаётся на мастер-пароле — это **осознанный паритет-компромисс** (фича «доступна везде»
в смысле «корректно деградирует», как FileVault: контракт общий, биометрия — опциональная
платформенная возможность). Зафиксировать в product-brief, что биометрия desktop — post-MVP.

## 4. Потоки

**Включение биометрии** (vault уже разблокирован, dataKey в памяти):
1. `availability()` == Available, иначе тумблер недоступен.
2. `ensureKey(alias = "skerry.vault.bio.<deviceId>")`.
3. `wrap(alias, dataKey.bytes, prompt)` → `wrappedBio`.
4. Сохранить `wrappedBio` + метаданные в `vault.bio`. **Сразу затереть** копию dataKey.bytes,
   которую отдавали в `wrap` (см. §6).

**Разблокировка биометрией** (холодный старт, файл vault и vault.bio существуют):
1. Экран предлагает «Разблокировать биометрией» + «Ввести мастер-пароль».
2. `unwrap(alias, wrappedBio, prompt)` → `dataKey.bytes` → отдать в `FileVault` через **новый**
   внутренний путь `unlockWithDataKey(dataKey)` (НЕ через пароль). Требует расширения контракта
   `Vault` методом `unlockWithDataKey` или фабрикой — обсудить (см. открытые вопросы).
3. При ошибке/отмене/LockedOut — молча падаем на форму мастер-пароля.

**Выключение / паника / смена пароля:**
- Выключение: `deleteKey(alias)` + удалить `vault.bio`.
- Смена мастер-пароля: НЕ трогает `vault.bio` (dataKey неизменен) — но стоит предложить
  переподтвердить биометрию ради явности.
- Сброс vault (забыт пароль): удалить и vault, и vault.bio (биометрия без vault бессмысленна).

## 5. Модель угроз (кратко)

- **Кража файла vault + vault.bio** (без устройства): `wrappedBio` бесполезен — `bioKey` в
  secure-enclave чужого устройства не воспроизвести. Атакующий откатывается на offline-брутфорс
  `wrappedDataKey` через Argon2id — та же стойкость, что и без биометрии. ✔
- **Живое разблокированное устройство в руках атакующего:** биометрия его не ухудшает (он и так
  внутри). Auto-lock по таймауту/бэкграунду — отдельная защита (TODO, см. чеклист).
- **Подмена биометрии (новый отпечаток злоумышленника):** `setInvalidatedByBiometricEnrollment`/
  `biometryCurrentSet` инвалидируют bioKey → откат на пароль. ✔
- **Root/JB:** StrongBox/Secure Enclave снижают риск извлечения ключа, но при полной компрометации
  железа гарантий нет — мастер-пароль остаётся последней линией. Задокументировать честно.

## 6. Затирание секретов

`unwrap` возвращает `dataKey.bytes` как `ByteArray` (мутабельный) — `FileVault` копирует в `DataKey`
и вызывающий **обязан** затереть возвращённый массив (`fill(0)`), как уже делается для masterKey.
`wrap` принимает копию dataKey.bytes — её тоже затереть после вызова. Никаких `String` на пути
(в отличие от ionspin pwhash) — биометрический путь чище по этому пункту.

## 7. Тестируемость (что можно по TDD без устройства)

- `FakeBiometricKeyStore` (in-memory, конфигурируемый `availability`, `unwrap` возвращает то, что
  `wrap` положил) → тестировать **оркестрацию**: включение/выключение, выбор биометрия-vs-пароль,
  инвалидация, затирание, развязку от `changePassword`.
- Платформенные `actual` (Keystore/Keychain) — **только на устройстве/эмуляторе**, ручная проверка.
- Desktop `actual` = `NoHardware` — тривиальный юнит-тест.

## 8. Открытые вопросы (нужно решение пользователя)

1. **Расширение контракта `Vault`:** добавить `unlockWithDataKey(dataKey: DataKey): UnlockResult`
   (минимально-инвазивно) против отдельной фабрики? Первое проще, но расширяет публичный контракт
   и даёт путь разблокировки в обход пароля — security-чувствительно (надо ограничить видимость).
2. **Где хранить `wrappedBio`:** отдельный файл `vault.bio` рядом, или внутри Keychain/Keystore
   как зашифрованный blob? Файл проще и единообразно с vault.json; Keychain «чище» по инкапсуляции.
3. **Auto-lock:** биометрия повышает удобство → разумно одновременно ввести авто-лок по таймауту/
   уходу в фон (сейчас авто-лока нет вовсе — осознанный долг desktop MVP).
4. **Android minSdk 24:** `androidx.biometric` поддерживает API 23+, ок. Но `StrongBox`
   (`setIsStrongBoxBacked`) — API 28+; на 24–27 деградируем на TEE.

## 9. Предлагаемый порядок реализации (после одобрения)

1. Контракт `BiometricKeyStore` + `BiometricAvailability`/`BiometricResult` в `commonMain` +
   `FakeBiometricKeyStore` + TDD оркестрации (платформо-независимо).
2. Расширение `Vault`/`FileVault` путём `unlockWithDataKey` + `vault.bio` персист (TDD на
   FakeFileSystem) — **без** реальной биометрии, на фейке.
3. Android `actual` (Keystore + BiometricPrompt) → ручная проверка на устройстве.
4. iOS `actual` (Keychain + Secure Enclave + LAContext) → ручная проверка на устройстве.
5. UI: тумблер в настройках vault + кнопка «Разблокировать биометрией» на гейте.
6. Параллельно — auto-lock.
