package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandHistoryTest {

    @Test
    fun `records newest first and dedupes`() {
        val h = CommandHistory()
        h.record("ls")
        h.record("cd /tmp")
        h.record("ls") // повтор поднимается наверх, без дубля
        assertEquals(listOf("ls", "cd /tmp"), h.commands)
    }

    @Test
    fun `blank commands are ignored`() {
        val h = CommandHistory()
        h.record("   ")
        h.record("")
        assertTrue(h.commands.isEmpty())
    }

    @Test
    fun `capacity trims oldest`() {
        val h = CommandHistory(capacity = 2)
        h.record("a"); h.record("b"); h.record("c")
        assertEquals(listOf("c", "b"), h.commands)
    }

    @Test
    fun `suggestion returns newest matching longer entry`() {
        val h = CommandHistory()
        h.record("git status")
        h.record("git push origin main")
        assertEquals("git push origin main", h.suggestion("git p"))
        assertEquals("git status", h.suggestion("git s"))
        assertNull(h.suggestion("git status")) // равно префиксу — не подсказываем
        assertNull(h.suggestion("")) // пустой префикс
    }

    @Test
    fun `matches returns all prefix matches newest first`() {
        val h = CommandHistory()
        h.record("git status")
        h.record("git stash")
        assertEquals(listOf("git stash", "git status"), h.matches("git s"))
        assertTrue(h.matches("").isEmpty())
    }

    @Test
    fun `search finds substring matches newest first`() {
        val h = CommandHistory()
        h.record("git status")
        h.record("docker ps")
        h.record("git push")
        assertEquals(listOf("git push", "git status"), h.search("git"))
        assertEquals(listOf("docker ps"), h.search("ps")) // substring, не префикс
        assertTrue(h.search("").isEmpty())
    }
}

class AutocompleteEngineTest {

    private fun engine(vararg history: String) =
        AutocompleteEngine(CommandHistory().apply { history.reversed().forEach { record(it) } })

    @Test
    fun `tracks typed line from user bytes`() {
        val e = AutocompleteEngine()
        e.onUserInput("ls -l".encodeToByteArray())
        assertEquals("ls -l", e.currentLine)
    }

    @Test
    fun `backspace removes last char`() {
        val e = AutocompleteEngine()
        e.onUserInput("lss".encodeToByteArray())
        e.onUserInput(byteArrayOf(127)) // DEL
        assertEquals("ls", e.currentLine)
    }

    @Test
    fun `enter commits the line to history and resets`() {
        val e = AutocompleteEngine()
        val committed = e.onUserInput("uptime\r".encodeToByteArray())
        assertEquals("uptime", committed)
        assertEquals("", e.currentLine)
        // повторный набор префикса подсказывает закоммиченную команду
        e.onUserInput("up".encodeToByteArray())
        assertEquals("uptime", e.suggestion())
    }

    @Test
    fun `suggestion tail is the completion after the typed prefix`() {
        val e = engine("systemctl restart nginx")
        e.onUserInput("systemctl re".encodeToByteArray())
        assertEquals("start nginx", e.suggestionTail())
    }

    @Test
    fun `accept returns tail bytes and extends the line`() {
        val e = engine("docker compose up -d")
        e.onUserInput("docker com".encodeToByteArray())
        val bytes = e.acceptSuggestion()
        assertEquals("pose up -d", bytes?.decodeToString())
        assertEquals("docker compose up -d", e.currentLine)
    }

    @Test
    fun `builtins complete when history is empty`() {
        val e = AutocompleteEngine()
        e.onUserInput("gti".encodeToByteArray()) // нет совпадений
        assertNull(e.suggestion())
        e.onUserInput(byteArrayOf(127, 127, 127)) // стереть
        e.onUserInput("git st".encodeToByteArray())
        assertEquals("git status", e.suggestion())
    }

    @Test
    fun `arrow-key escape sequence clears the line without corrupting it`() {
        val e = AutocompleteEngine()
        e.onUserInput("ls".encodeToByteArray())
        e.onUserInput(byteArrayOf(27, '['.code.toByte(), 'A'.code.toByte())) // ESC [ A (стрелка вверх)
        assertEquals("", e.currentLine)
    }

    @Test
    fun `reset clears the tracked line without recording to history`() {
        val e = AutocompleteEngine()
        e.onUserInput("secretpass".encodeToByteArray())
        e.reset() // напр. вход в режим без эха (ввод пароля) — не коммитим
        assertEquals("", e.currentLine)
        // После сброса и «ввода Enter» ничего не должно было попасть в историю: набор того же
        // префикса не даёт подсказки (её источник — только история/типовые, а "secretpass" там нет).
        e.onUserInput("secret".encodeToByteArray())
        assertNull(e.suggestion())
    }

    @Test
    fun `no suggestion after a trailing space`() {
        val e = engine("git status")
        e.onUserInput("git ".encodeToByteArray())
        assertNull(e.suggestionTail())
    }

    @Test
    fun `cycle advances through candidates and wraps`() {
        val e = engine("backupdb", "backupfiles")
        e.onUserInput("back".encodeToByteArray())
        assertEquals("backupdb", e.suggestion())
        e.cycleSuggestion()
        assertEquals("backupfiles", e.suggestion())
        e.cycleSuggestion() // wrap
        assertEquals("backupdb", e.suggestion())
    }

    @Test
    fun `cycle position resets when the line changes`() {
        val e = engine("backupdb", "backupfiles")
        e.onUserInput("back".encodeToByteArray())
        e.cycleSuggestion()
        assertEquals("backupfiles", e.suggestion())
        e.onUserInput("u".encodeToByteArray()) // "backu" — строка изменилась
        assertEquals("backupdb", e.suggestion()) // курсор цикла сброшен на первый кандидат
    }

    @Test
    fun `completes known subcommand when history is empty`() {
        val e = AutocompleteEngine()
        e.onUserInput("git pus".encodeToByteArray())
        assertEquals("git push", e.suggestion())
        e.onUserInput(byteArrayOf(127, 127, 127, 127, 127, 127, 127)) // стереть
        e.onUserInput("docker ru".encodeToByteArray())
        assertEquals("docker run", e.suggestion())
    }

    @Test
    fun `completes a path token seen earlier in the session`() {
        val e = AutocompleteEngine()
        e.onUserInput("cat /etc/nginx/nginx.conf\r".encodeToByteArray())
        e.onUserInput("vim /etc/ng".encodeToByteArray())
        assertEquals("vim /etc/nginx/nginx.conf", e.suggestion())
        assertEquals("inx/nginx.conf", e.suggestionTail())
    }
}
