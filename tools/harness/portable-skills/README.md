# Переносимый комплект разработки SkerrySSH

20 практических Codex-скиллов на русском языке. Они описывают устройство проекта, подготовку нового ПК, повседневную разработку, тесты, безопасность, платформенные особенности и доставку. Код, имена символов и обязательные английские форматы сохранены без перевода.

Сверено с `origin/main` на 2026-10-20, commit `5254d775f096e9c5a8461d77c6b18680d6cd7aa8`. Код и действующие AGENTS.md/docs имеют приоритет над этим навигационным snapshot. Продуктовые исходники этим комплектом не меняются.

## Состав комплекта

- `skills/` — 20 самостоятельных папок `SKILL.md` с `agents/openai.yaml` для обнаружения в Codex.
- [Архитектура](skills/skerry-architecture/SKILL.md) и карта 1890 tracked source paths с именами Kotlin-объявлений; SHA256 29 ключевых contracts/build/workflow files для обнаружения drift.
- [Read-only диагностика окружения](skills/skerry-new-machine/scripts/inspect_environment.py).
- [Ограниченный Linux runner](skills/skerry-new-machine/scripts/bounded_run.py): 3 GiB process group, zero swap, CPU 200%, preflight, отдельный lock.
- [Установщик](tools/install.py), сохраняющий существующие скиллы, и [проверка комплекта](tools/verify_bundle.py).
- `SHA256SUMS.txt` для проверки целостности всего комплекта; поведенческие проверки установщика и утилит в `tools/test_helpers.py`.

Комплект не содержит clone проекта, сборочных кэшей, production vault, ключей, токенов, .env, локальной истории агентов, signing credentials и старых receipts. Они не нужны для установки скиллов. После clone репозитория комплект уже находится в `tools/harness/portable-skills`; его также можно скопировать целиком отдельно.

## Установка на новом ПК

Нужен Python 3.11+. После clone открыть терминал в корне репозитория и выполнить:

```sh
cd tools/harness/portable-skills
```

Для отдельно скопированного комплекта открыть его каталог. Затем:

```sh
python3 tools/verify_bundle.py
python3 tools/install.py --dry-run
python3 tools/install.py
```

Установщик по умолчанию копирует в `$CODEX_HOME/skills`, если CODEX_HOME задан, иначе `~/.codex/skills`. Он не требует sudo, не запускает package manager, не изменяет Git/IDE/профиль оболочки и не подменяет уже установленные одноимённые скиллы. Если есть конфликт, остановится **до** установки; выбрать другой каталог через `--dest` или осознанно перенести старый набор вручную. Автоматического overwrite нет.

```sh
# Кастомное расположение, например доступный агенту каталог skills:
python3 tools/install.py --dest /path/to/skills
```

Для репозиторного `.agents/skills` тоже допустим `--dest /path/to/skerry/.agents/skills`, если используемый хост обнаруживает его. Этот путь в исследованном Skerry gitignored: устанавливать его из отслеживаемого комплекта после clone. Не рассчитывать, что git push перенесёт игнорируемые skills.

После установки открыть новый chat/обновить обнаружение скиллов в используемом Codex. Если список не обновился, перезапустить приложение; не редактировать его внутреннюю базу. Для другого агента загрузить нужный SKILL.md и references его штатным способом. Автообнаружение/agents metadata — возможности конкретного хоста, не гарантия всех IDE.

## Первый запрос агенту

```text
Используй $skerry-start и $skerry-new-machine.
Репозиторий находится в /мой/путь/skerry-app.
Подготовь рабочее окружение по текущим AGENTS.md и docs, проверь JDK/SDK/display,
выполни диагностику и нужный smoke под ресурсными ограничениями.
Секреты и production-данные не переносить. Сначала сообщи реальные пробелы окружения.
```

Для обычной задачи:

```text
Используй $skerry-change-workflow и $skerry-remote-desktop.
Исправь [конкретное наблюдаемое поведение], сохрани desktop/Android parity.
Приёмка: [наблюдаемые результаты]. Commit/push пока не требуются.
```

Для анализа без реализации:

```text
Используй $skerry-architecture и подходящий domain skill.
Проанализируй issue [номер], пока ничего не реализуй.
Покажи трассу по коду, факты, гипотезы и предлагаемые acceptance checks.
```

Для GLM/OpenCode — только если пользователь выбрал этот режим:

```text
Используй $skerry-agent-orchestration: основной исполнитель GLM через OpenCode.
Открой настоящий живой интерфейс OpenCode, проверь модель и выполни [задачу].
Codex оркестрирует и независимо проверяет; product code пишет GLM.
```

Эти примеры не дают разрешения на внешние действия и не меняют текущую задачу автоматически.

## Полный каталог

| Скилл | Что покрывает |
|---|---|
| [skerry-start](skills/skerry-start/SKILL.md) | Начало работы, приоритет источников, выбор нужных скиллов, обязательные ограничения |
| [skerry-new-machine](skills/skerry-new-machine/SKILL.md) | JDK/Python/SDK/display, clone/IDE, bounded runs, Windows/macOS gaps, безопасный перенос |
| [skerry-architecture](skills/skerry-architecture/SKILL.md) | Пять модулей, source sets, wiring, домены, поиск цепочки поведения |
| [skerry-change-workflow](skills/skerry-change-workflow/SKILL.md) | Feature/bug/refactor, tests first, implementation, gate/review, handoff |
| [skerry-testing](skills/skerry-testing/SKILL.md) | common/JVM/UI/server/Android тесты, RED, fakes, live boundaries |
| [skerry-harness](skills/skerry-harness/SKILL.md) | Planner, receipts, JSON envelope, review tokens/findings, hooks, CI и internals |
| [skerry-kotlin-concurrency](skills/skerry-kotlin-concurrency/SKILL.md) | Cancellation, re-entry, transactions, queues, ownership, TOCTOU |
| [skerry-ui](skills/skerry-ui/SKILL.md) | Design primitives, common form state, en/ru/zh/tr/de, IME, keyboard, a11y |
| [skerry-terminal](skills/skerry-terminal/SKILL.md) | Emulator, reflow, shell marks, snapshot publication, input/clipboard/recordings |
| [skerry-connections-files](skills/skerry-connections-files/SKILL.md) | SSH/jumps/proxy, SFTP queue/window/cancel, tunnels, Mosh/serial/Telnet, runbooks |
| [skerry-remote-desktop](skills/skerry-remote-desktop/SKILL.md) | RDP/VNC negotiation, display, TLS/trust, graphics/codecs/audio/input/lifecycle |
| [skerry-vault-security](skills/skerry-vault-security/SKILL.md) | Vault crypto/persistence, identity/AAD, secrets, keystore/biometrics, hostile inputs |
| [skerry-sync-teams](skills/skerry-sync-teams/SKILL.md) | Delta/cursors/filtering, pairing, web access, teams/scopes/key rotation, live share |
| [skerry-server](skills/skerry-server/SKILL.md) | Ktor/auth/routes, SQL/migrations/quotas, web/CLI/metrics, Docker/serverOnly |
| [skerry-ai](skills/skerry-ai/SKILL.md) | Policy/routing/redaction/confirmation, bounded streaming, native isolation/ISA |
| [skerry-android](skills/skerry-android/SKILL.md) | Activity/FGS/session ownership, wake locks, permissions, ABI/IME/USB/media |
| [skerry-performance](skills/skerry-performance/SKILL.md) | Baseline/profile/metrics, terminal/vault/transfer/startup workloads, keep/revert |
| [skerry-release-github](skills/skerry-release-github/SKILL.md) | Mandatory Issue/PR format, CI/merge, versions/signing/packages, draft/server release |
| [skerry-agent-orchestration](skills/skerry-agent-orchestration/SKILL.md) | Видимые исполнители, selected profiles, required reviews, explicit GLM mode |
| [skerry-troubleshooting](skills/skerry-troubleshooting/SKILL.md) | Симптомы, причины и bounded recovery для toolchain/gate/network/native/platform |

Не загружать весь набор в каждый prompt: names/descriptions нужны для выбора, SKILL.md — для работы, refs — только при необходимости. Подробный каталог абстракций проекта остаётся в docs/coding-guidelines.md.

## Проверка актуальности после clone/pull

```sh
python3 tools/verify_bundle.py --repo /path/to/skerry
```

Несовпадение commit само по себе не ошибка; проверка сравнивает SHA29 ключевых файлов. Drift означает перечитать изменившиеся источники и обновить соответствующий скилл. Совпадение SHA не доказывает актуальность всех доменных деталей: если меняется подсистема, исследовать её текущее состояние. Карта исходников — navigation snapshot, не компиляторный semantic graph.

При обновлении комплекта менять инструкции/refs по фактическому коду, прогонять валидацию каждого скилла штатным skill-creator validator, проверить вспомогательные scripts, обновить source-map по изменившимся источникам и SHA256 manifest. Установщик не делает автоматическое обновление — чтобы не уничтожить локальные дополнения.

## Практические границы

Скиллы устраняют необходимость заново искать уже известные правила, но не заменяют установку toolchain, доступ к Git/SDK, реальную модель агента, устройства и release credentials. Linux runner имеет реальные process-tree limits; Windows/macOS требуют проверенного эквивалента и отдельно не считаются live-tested.

Диагностика показывает фактические JDK/SDK/display текущего ПК. В комплекте есть порядок исправления окружения без копирования старых абсолютных путей. Проверки комплекта не заменяют продуктовые сборки и проверку на устройстве.

## Проверки и сопровождение

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/test_helpers.py
python3 tools/verify_bundle.py --repo ../../..
```

Полный `python3 tools/harness/selftest.py` из корня репозитория также запускает эти проверки. Для намеренного обновления файлов пересчитать manifest после тестов:

```sh
python3 - <<'PY'
from pathlib import Path
import hashlib
root = Path.cwd()
manifest = root / "SHA256SUMS.txt"
files = sorted(p for p in root.rglob("*") if p.is_file() and p != manifest)
manifest.write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(root).as_posix()}\n" for p in files), encoding="utf-8")
PY
```

Не включать в manifest generated caches или локальные файлы. `verify_bundle.py` проверяет точный набор файлов и сообщает о drift; смена SHA без проверки смысловой актуальности скиллов недостаточна.
