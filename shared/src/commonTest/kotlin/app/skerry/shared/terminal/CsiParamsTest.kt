package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsiParamsTest {

    private fun parse(raw: String): CsiParams = CsiParams.of(raw)

    private fun CsiParams.values(): List<Int> = (0 until size).map { at(it, ABSENT) }

    @Test
    fun `no bytes means no parameters`() {
        val p = parse("")
        assertEquals(0, p.size)
        assertEquals(ABSENT, p.at(0, ABSENT))
    }

    @Test
    fun `fields split on semicolons and an empty field is omitted`() {
        assertEquals(listOf(1, CsiParams.OMITTED, 3), parse("1;;3").values())
        assertEquals(listOf(CsiParams.OMITTED, CsiParams.OMITTED), parse(";").values())
        assertEquals(listOf(12, CsiParams.OMITTED), parse("12;").values())
    }

    @Test
    fun `a value past the cap saturates instead of wrapping`() {
        assertEquals(listOf(CsiParams.MAX_VALUE), parse("99999999999999999999").values())
    }

    @Test
    fun `a leading private marker is split off`() {
        val p = parse("?1049")
        assertEquals('?', p.marker)
        assertEquals(listOf(1049), p.values())
        assertEquals(CsiParams.NO_MARKER, parse("1").marker)
    }

    @Test
    fun `a marker byte inside a field spoils only that field`() {
        assertEquals(listOf(1, CsiParams.OMITTED, 3), parse("1;2?;3").values())
    }

    @Test
    fun `colon subparameters are flagged against their parameter`() {
        val p = parse("38:2::1:2:3;1")
        assertEquals(listOf(38, 2, CsiParams.OMITTED, 1, 2, 3, 1), p.values())
        assertFalse(p.isSub(0))
        assertTrue((1..5).all { p.isSub(it) })
        assertFalse(p.isSub(6))
    }

    @Test
    fun `fields past the cap are dropped without failing`() {
        val p = parse((1..500).joinToString(";"))
        assertEquals(CsiParams.MAX_PARAMS, p.size)
        assertEquals(1, p.at(0, ABSENT))
    }

    @Test
    fun `reset forgets the previous sequence`() {
        val p = parse("?5;6")
        p.reset()
        "7".forEach { p.accept(it.code) }
        p.finish()
        assertEquals(CsiParams.NO_MARKER, p.marker)
        assertEquals(listOf(7), p.values())
    }

    private companion object {
        const val ABSENT = -99
    }
}
