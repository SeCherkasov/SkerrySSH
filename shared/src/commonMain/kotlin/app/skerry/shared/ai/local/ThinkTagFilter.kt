package app.skerry.shared.ai.local

/**
 * Вырезает из стриминга локальной модели ЛИДИРУЮЩИЙ блок рассуждений `<think>…</think>`
 * (Qwen3 эмитит его даже с `/no_think` — пустым). Работает по дельтам: тег может прийти
 * разрезанным на куски. Всё после закрывающего тега (без ведущих переводов строк) отдаётся
 * как есть; `<think>` в середине ответа — контент, не режем.
 *
 * Один экземпляр — на одну генерацию (состояние не переиспользуется между стримами).
 */
class ThinkTagFilter {

    private enum class State { LEADING, INSIDE, AFTER, PASS }

    private var state = State.LEADING
    private val buffer = StringBuilder()

    /** Пропустить очередную дельту через фильтр; возвращает текст к эмиту (может быть пустым). */
    fun feed(text: String): String {
        if (state == State.PASS) return text
        buffer.append(text)
        return drain()
    }

    /** Хвост по завершении стрима: буфер, так и не оказавшийся `<think>`-блоком. */
    fun tail(): String = when (state) {
        State.LEADING -> buffer.toString()
        else -> ""
    }

    private fun drain(): String {
        if (state == State.LEADING) {
            val lead = buffer.toString().trimStart()
            when {
                lead.startsWith(OPEN) -> {
                    state = State.INSIDE
                    val inside = lead.removePrefix(OPEN)
                    buffer.clear()
                    buffer.append(inside)
                }
                // Всё ещё может оказаться началом "<think>" (учитывая ведущие пробелы) — копим.
                OPEN.startsWith(lead) -> return ""
                else -> {
                    state = State.PASS
                    val out = buffer.toString()
                    buffer.clear()
                    return out
                }
            }
        }
        if (state == State.INSIDE) {
            val closeAt = buffer.indexOf(CLOSE)
            if (closeAt < 0) {
                // Держим только хвост, способный быть началом "</think>" — рассуждения не копим.
                val keep = buffer.takeLast(CLOSE.length - 1).toString()
                buffer.clear()
                buffer.append(keep)
                return ""
            }
            val rest = buffer.substring(closeAt + CLOSE.length)
            buffer.clear()
            state = State.AFTER
            buffer.append(rest)
        }
        // AFTER: срезаем переводы строк сразу за </think>, дальше — чистый passthrough.
        val out = buffer.toString().trimStart()
        return if (out.isEmpty()) {
            buffer.clear()
            ""
        } else {
            state = State.PASS
            buffer.clear()
            out
        }
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
