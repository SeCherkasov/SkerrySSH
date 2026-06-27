package app.skerry.shared.snippet

import kotlinx.serialization.Serializable

/**
 * Сохранённый сниппет (модель Termius): именованная команда/скрипт для повторного запуска в терминале.
 * Самостоятельный объект, а не часть открытой сессии — идентичность это стабильный [id] (назначается
 * при создании, не меняется при правках). [label] — отображаемое имя, [command] — текст, который
 * вставляется в активный терминал и исполняется (с переводом строки). [tags] — пользовательские
 * метки для группировки/поиска (#monitoring, #disk).
 *
 * Секретов сниппет не содержит и в vault не хранится — это plain-конфиг рядом с хостами/туннелями.
 */
@Serializable
data class Snippet(
    val id: String,
    val label: String,
    val command: String,
    val tags: List<String> = emptyList(),
)
