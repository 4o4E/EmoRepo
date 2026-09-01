package top.e404.emorepo.experiment.lsposed

import org.junit.Assert.assertEquals
import org.junit.Test

class QqMessageSenderTest {
    @Test
    fun `static pictures use custom emoticon subtype in ordinary chats`() {
        assertEquals(7, emoticonPicSubtype(chatType = 1, originalSubtype = 0))
        assertEquals(7, emoticonPicSubtype(chatType = 2, originalSubtype = 0))
    }

    @Test
    fun `existing GIF subtype and guild subtype remain unchanged`() {
        assertEquals(1, emoticonPicSubtype(chatType = 2, originalSubtype = 1))
        assertEquals(4, emoticonPicSubtype(chatType = 2, originalSubtype = 4))
        assertEquals(0, emoticonPicSubtype(chatType = 4, originalSubtype = 0))
    }
}
