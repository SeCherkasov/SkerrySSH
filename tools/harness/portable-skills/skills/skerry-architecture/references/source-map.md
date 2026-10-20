# Карта исследованного snapshot

Дата: 2026-10-20. Commit: `5254d775f096e9c5a8461d77c6b18680d6cd7aa8`. Ветка: `main`.

Snapshot сверен с `origin/main` на указанную дату; последующие изменения требуют повторной проверки. Состояние CI и все платформы заново здесь не проверялись.

В [source-map.json](source-map.json):

- SHA256 29 ключевых contracts/build/workflow sources для обнаружения drift.
- 1890 tracked source paths по модулям/source sets.
- Имена объявлений Kotlin для навигации; regex может находить nested declarations, это не полноценный semantic index.
- Только относительные публичные repository paths; нет secrets, local.properties, env, remote credentials или private history.

| Область | Индексированных файлов |
|---|---:|
| androidApp | 20 |
| composeApp | 1138 |
| server | 97 |
| shared | 610 |
| sync-wire | 4 |
| tools | 21 |

## Как искать

```sh
rg -n 'RdpConnectRequest|SyncCoordinator|FileVault' /path/to/skill/references/source-map.json
rg --files shared/src composeApp/src androidApp/src server/src sync-wire/src
rg -n 'class ClassName|interface ContractName|fun functionName' shared/src composeApp/src server/src
```

## Как обновлять

Использовать текущий git ls-files и существующие files, не весь домашний каталог. После обновления Skerry сначала проверить ключевые contracts/build/workflows, затем исправить affected skills и заново validate/archive. Не просто менять дату/commit, оставляя старые утверждения. Команда `tools/verify_bundle.py --repo /path/to/skerry` обнаруживает расхождение ключевых SHA, но не доказывает актуальность всех смысловых утверждений. Карта не содержит исходников и не заменяет clone.
