package top.e404.emorepo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PackEditDraftTest {
    @Test
    fun selectedItemsMoveToFrontWithoutChangingRelativeOrder() {
        assertEquals(
            listOf("b", "d", "a", "c"),
            moveSelectedToFront(listOf("a", "b", "c", "d"), setOf("d", "b")),
        )
    }

    @Test
    fun emptySelectionKeepsOriginalOrder() {
        assertEquals(listOf("a", "b"), moveSelectedToFront(listOf("a", "b"), emptySet()))
    }
}
