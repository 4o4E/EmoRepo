package top.e404.emorepo.ui.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RangeSelectionTest {
    private val ordered = listOf("a", "b", "c", "d", "e")

    @Test
    fun `selects forward range including both endpoints`() {
        assertEquals(setOf("b", "c", "d"), selectContinuousRange(ordered, emptySet(), 1, 3))
    }

    @Test
    fun `selects reverse range with the same visual order semantics`() {
        assertEquals(setOf("b", "c", "d"), selectContinuousRange(ordered, emptySet(), 3, 1))
    }

    @Test
    fun `retains selection that existed before drag`() {
        assertEquals(setOf("a", "c", "d"), selectContinuousRange(ordered, setOf("a"), 2, 3))
    }

    @Test
    fun `rejects endpoints outside the ordered list`() {
        assertThrows(IllegalArgumentException::class.java) {
            selectContinuousRange(ordered, emptySet(), 0, ordered.size)
        }
    }
}
