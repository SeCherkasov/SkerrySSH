package app.skerry.ui.snippet

import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.SnippetTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateParamSeedTest {

    private fun param(cmd: String) = SnippetTemplate.parse(cmd).single() as SnippetSegment.Variable

    @Test
    fun `free-text parameter prefers the previous value over the inline default`() {
        val v = param("\${{container:web-1}}")
        assertEquals("web-1", paramSeed(v, previous = null))
        assertEquals("db-2", paramSeed(v, previous = "db-2"))
        assertEquals("", paramSeed(param("\${{container}}"), previous = null))
    }

    @Test
    fun `choice parameter starts on the default option`() {
        val v = param("\${{env:dev|staging|prod}}")
        assertEquals("dev", paramSeed(v, previous = null))
    }

    @Test
    fun `choice parameter keeps the previous value only while it is still offered`() {
        val v = param("\${{env:dev|staging|prod}}")
        assertEquals("prod", paramSeed(v, previous = "prod"))
        // A remembered value the template no longer offers must not resurrect as a choice.
        assertEquals("dev", paramSeed(v, previous = "qa"))
    }

    @Test
    fun `choice parameter without a default starts on the first option`() {
        assertEquals("dev", paramSeed(param("\${{env:|dev|prod}}"), previous = null))
    }

    /**
     * The inline default is the template's own text, and a template can be shared: the field is
     * seeded with what will be spliced, not with something the run then quietly rewrites.
     */
    @Test
    fun `an inline default is filtered like the option list is`() {
        assertEquals("etc", paramSeed(param("\${{path:\u202Eetc}}"), previous = null))
        assertEquals("", paramSeed(param("\${{path:\u200B}}"), previous = null))
    }
}
