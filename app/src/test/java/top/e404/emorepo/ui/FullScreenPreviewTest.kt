package top.e404.emorepo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FullScreenPreviewTest {
    @Test
    fun `uses original image mime type when forwarding`() {
        assertEquals("image/png", imageMimeType("png"))
        assertEquals("image/jpeg", imageMimeType("jpeg"))
        assertEquals("image/gif", imageMimeType("GIF"))
        assertEquals("image/webp", imageMimeType("webp"))
    }
}
