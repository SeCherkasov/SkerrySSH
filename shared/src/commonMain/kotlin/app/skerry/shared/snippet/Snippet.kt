package app.skerry.shared.snippet

import kotlinx.serialization.Serializable

/**
 * Сохранённый сниппет (модель Termius): именованная команда/скрипт для повторного запуска в терминале.
 * Самостоятельный объект, а не часть открытой сессии — идентичность это стабильный [id] (назначается
 * при создании, не меняется при правках). [label] — отображаемое имя, [command] — текст, который
 * вставляется в активный терминал и исполняется (с переводом строки). [tags] — пользовательские
 * метки для группировки/поиска (#monitoring, #disk).
 *
 * [runOnHostId] — куда «Run now» отправляет команду по умолчанию: `null` — текущий активный
 * терминал (вставка на месте); непустой id — конкретный хост из каталога (открыть/использовать
 * сессию к нему и выполнить там). [shortcut] — глобальная горячая клавиша запуска в каноничном
 * виде (`Ctrl+Shift+D`), `null` — без хоткея. Оба поля с дефолтами: старый `snippets.json` без
 * них читается как есть (backward-compat).
 *
 * Секретов сниппет не содержит и в vault не хранится — это plain-конфиг рядом с хостами/туннелями.
 */
@Serializable
data class Snippet(
    val id: String,
    val label: String,
    val command: String,
    val tags: List<String> = emptyList(),
    val runOnHostId: String? = null,
    val shortcut: String? = null,
)
