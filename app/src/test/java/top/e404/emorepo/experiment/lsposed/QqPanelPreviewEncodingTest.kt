package top.e404.emorepo.experiment.lsposed

import org.junit.Assert.assertEquals
import org.junit.Test

class QqPanelPreviewEncodingTest {
    @Test
    fun `all supported systems select a lossless preview encoding`() {
        assertEquals(QqPanelPreviewEncoding.PNG, qqPanelPreviewEncoding(24))
        assertEquals(QqPanelPreviewEncoding.PNG, qqPanelPreviewEncoding(29))
        assertEquals(QqPanelPreviewEncoding.LOSSLESS_WEBP, qqPanelPreviewEncoding(30))
        assertEquals(QqPanelPreviewEncoding.LOSSLESS_WEBP, qqPanelPreviewEncoding(36))
    }
}
