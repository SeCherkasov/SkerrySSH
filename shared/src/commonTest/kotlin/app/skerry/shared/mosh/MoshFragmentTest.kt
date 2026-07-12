package app.skerry.shared.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoshFragmentTest {

    @Test
    fun `fragment header is 8-byte id plus 2-byte index with final bit`() {
        val f = MoshFragment(id = 1u, index = 0, final = true, data = byteArrayOf(7))
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0x80.toByte(), 0, 7),
            f.encode(),
        )
        val parsed = MoshFragment.parse(f.encode())!!
        assertEquals(1uL, parsed.id)
        assertEquals(0, parsed.index)
        assertTrue(parsed.final)
        assertContentEquals(byteArrayOf(7), parsed.data)
    }

    @Test
    fun `parse rejects datagrams shorter than the header`() {
        assertNull(MoshFragment.parse(ByteArray(9)))
    }

    @Test
    fun `splitter honors max fragment size and marks the last fragment final`() {
        val payload = ByteArray(250) { it.toByte() }
        val fragments = MoshFragmenter().split(payload, maxFragmentSize = 100)
        assertEquals(3, fragments.size)
        assertEquals(listOf(0, 1, 2), fragments.map { it.index })
        assertEquals(listOf(false, false, true), fragments.map { it.final })
        assertTrue(fragments.all { it.id == fragments[0].id })
        assertContentEquals(payload, fragments.flatMap { it.data.toList() }.toByteArray())
    }

    @Test
    fun `splitter increments the instruction id per call`() {
        val fragmenter = MoshFragmenter()
        val a = fragmenter.split(byteArrayOf(1), maxFragmentSize = 100)[0]
        val b = fragmenter.split(byteArrayOf(2), maxFragmentSize = 100)[0]
        assertEquals(a.id + 1u, b.id)
    }

    @Test
    fun `assembler reassembles fragments arriving in order`() {
        val payload = ByteArray(250) { (it * 3).toByte() }
        val fragments = MoshFragmenter().split(payload, maxFragmentSize = 100)
        val assembler = MoshFragmentAssembler()
        assertNull(assembler.add(fragments[0]))
        assertNull(assembler.add(fragments[1]))
        assertContentEquals(payload, assembler.add(fragments[2]))
    }

    @Test
    fun `assembler drops stale fragments of an older instruction`() {
        val fragmenter = MoshFragmenter()
        val old = fragmenter.split(ByteArray(200), maxFragmentSize = 100)
        val fresh = fragmenter.split(byteArrayOf(9), maxFragmentSize = 100)
        val assembler = MoshFragmentAssembler()
        assertNull(assembler.add(old[0]))
        assertContentEquals(byteArrayOf(9), assembler.add(fresh[0]))
        // A late fragment of the abandoned old instruction must not resurrect it.
        assertNull(assembler.add(old[1]))
    }

    @Test
    fun `assembler ignores duplicated fragments`() {
        val fragments = MoshFragmenter().split(ByteArray(200) { it.toByte() }, maxFragmentSize = 100)
        val assembler = MoshFragmentAssembler()
        assertNull(assembler.add(fragments[0]))
        assertNull(assembler.add(fragments[0]))
        val out = assembler.add(fragments[1])
        assertContentEquals(ByteArray(200) { it.toByte() }, out)
        assertFalse(out === null)
    }
}
