package top.e404.emorepo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
