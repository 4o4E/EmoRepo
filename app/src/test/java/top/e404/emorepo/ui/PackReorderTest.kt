package top.e404.emorepo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.e404.emorepo.protocol.pack.PackIndexRecord

class PackReorderTest {
    @Test
    fun movesItemForwardAndBackward() {
        assertEquals(listOf("b", "c", "a"), movePackItem(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), movePackItem(listOf("a", "b", "c"), 2, 0))
    }

    @Test
    fun rejectsInvalidIndices() {
        assertThrows(IllegalArgumentException::class.java) {
            movePackItem(listOf("a"), 0, 1)
        }
    }

    @Test
    fun movesArrangementByStablePackNameWithoutChangingCollapsedField() {
        val records = listOf(
            PackIndexRecord("normal"),
            PackIndexRecord("collapsed", collapsed = true),
        )

        val moved = movePackItemByName(records, "collapsed", "normal")

        assertEquals(listOf("collapsed", "normal"), moved.map { it.name })
        assertEquals(listOf(true, false), moved.map { it.collapsed })
    }
}
