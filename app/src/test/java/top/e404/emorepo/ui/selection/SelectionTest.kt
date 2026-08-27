package top.e404.emorepo.ui.selection

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTest {
    @Test
    fun `adds an unselected item`() {
        assertEquals(setOf("a", "b"), toggleSelection(setOf("a"), "b"))
    }

    @Test
    fun `removes a selected item`() {
        assertEquals(setOf("a"), toggleSelection(setOf("a", "b"), "b"))
    }
}
