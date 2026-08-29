package top.e404.emorepo.experiment.lsposed

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class CachedFileLeaseTest {
    @Test
    fun `close releases cache entry only once`() {
        var releases = 0
        val lease = CachedFileLease(File("cached.gif")) { releases += 1 }

        lease.close()
        lease.close()

        assertEquals(1, releases)
    }
}
