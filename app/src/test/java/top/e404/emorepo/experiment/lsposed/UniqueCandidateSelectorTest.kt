package top.e404.emorepo.experiment.lsposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UniqueCandidateSelectorTest {
    @Test
    fun `唯一有效候选会被选中`() {
        val selected = requireUniqueValidCandidate("测试目标", listOf("wrong", "right")) {
            it == "right"
        }

        assertEquals("right", selected)
    }

    @Test
    fun `没有有效候选时拒绝继续`() {
        assertThrows(IllegalStateException::class.java) {
            requireUniqueValidCandidate("测试目标", listOf("wrong")) { false }
        }
    }

    @Test
    fun `多个有效候选时拒绝猜测`() {
        assertThrows(IllegalStateException::class.java) {
            requireUniqueValidCandidate("测试目标", listOf("first", "second")) { true }
        }
    }
}
