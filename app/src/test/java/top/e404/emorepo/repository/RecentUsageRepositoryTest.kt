package top.e404.emorepo.repository

import java.io.File
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.recent.RecentUsageRecord

class RecentUsageRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recordUseKeepsNewestAndAppliesLimit() {
        val repository = repository(maximumRecords = 2)

        repository.recordUse("cats", "a.png", 10)
        repository.recordUse("dogs", "b.gif", 20)
        repository.recordUse("cats", "a.png", 30)
        repository.recordUse("other", "c.webp", 15)

        assertEquals(
            listOf(
                RecentUsageRecord("cats", "a.png", 30),
                RecentUsageRecord("dogs", "b.gif", 20),
            ),
            repository.readCurrentDevice(),
        )
    }

    @Test
    fun zeroLimitDoesNotCreateDeviceFile() {
        val repository = repository(maximumRecords = 0)

        repository.recordUse("cats", "a.png", 10)

        assertTrue(repository.readCurrentDevice().isEmpty())
        assertFalse(File(temporaryFolder.root, "repository/recent/android-test.csv").exists())
    }

    @Test
    fun trimImmediatelyAppliesChangedLimit() {
        val original = repository(maximumRecords = 20)
        original.recordUse("cats", "a.png", 30)
        original.recordUse("dogs", "b.gif", 20)
        original.recordUse("other", "c.webp", 10)

        val reduced = repository(maximumRecords = 1)
        reduced.trimCurrentDevice()

        assertEquals(listOf(RecentUsageRecord("cats", "a.png", 30)), reduced.readCurrentDevice())
    }

    @Test
    fun removeOnlyChangesCurrentDeviceFile() {
        val current = repository(deviceId = "android-current")
        val other = repository(deviceId = "android-other")
        current.recordUse("cats", "a.png", 10)
        other.recordUse("cats", "a.png", 20)

        current.remove("cats", "a.png")

        assertTrue(current.readCurrentDevice().isEmpty())
        assertEquals(listOf(RecentUsageRecord("cats", "a.png", 20)), other.readCurrentDevice())
    }

    @Test
    fun moveChangesPackageAndNameButPreservesTime() {
        val repository = repository()
        repository.recordUse("source", "a.jpeg", 10)

        repository.move("source", "a.jpeg", "target", "a.jpg")

        assertEquals(
            listOf(RecentUsageRecord("target", "a.jpg", 10)),
            repository.readCurrentDevice(),
        )
    }

    @Test
    fun mergedViewUsesNewestAcrossDevices() {
        val current = repository(deviceId = "android-current")
        val other = repository(deviceId = "android-other")
        current.recordUse("cats", "a.png", 10)
        other.recordUse("cats", "a.png", 20)
        current.recordUse("dogs", "b.gif", 30)

        assertEquals(
            listOf(
                RecentUsageRecord("dogs", "b.gif", 30),
                RecentUsageRecord("cats", "a.png", 20),
            ),
            current.readMerged(),
        )
    }

    @Test
    fun renameMergesTargetAndDeletesOldFile() {
        val old = repository(deviceId = "android-old")
        val target = repository(deviceId = "android-new")
        old.recordUse("cats", "a.png", 10)
        target.recordUse("dogs", "b.gif", 20)

        val renamed = old.renameDevice("android-new")

        assertEquals(
            listOf(
                RecentUsageRecord("dogs", "b.gif", 20),
                RecentUsageRecord("cats", "a.png", 10),
            ),
            renamed.readCurrentDevice(),
        )
        assertFalse(File(temporaryFolder.root, "repository/recent/android-old.csv").exists())
    }

    @Test
    fun invalidDeviceIdIsRejected() {
        assertThrows(ProtocolException::class.java) {
            repository(deviceId = "../escape")
        }
    }

    @Test
    fun generatedDeviceIdUsesConfirmedShape() {
        val deterministic = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.indices.forEach { index -> bytes[index] = (index + 1).toByte() }
            }
        }

        assertEquals("android-01020304", RecentUsageRepository.generateDeviceId(deterministic))
    }

    @Test
    fun defaultMaximumRecordsIsThirty() {
        assertEquals(30, repository().maximumRecords)
    }

    private fun repository(
        deviceId: String = "android-test",
        maximumRecords: Int = RecentUsageRepository.DEFAULT_MAXIMUM_RECORDS,
    ): RecentUsageRepository = RecentUsageRepository(
        File(temporaryFolder.root, "repository"),
        deviceId,
        maximumRecords,
    )
}
