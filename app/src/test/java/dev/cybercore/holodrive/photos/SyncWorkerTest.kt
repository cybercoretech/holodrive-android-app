package dev.cybercore.holodrive.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerTest {
    @Test fun phoneOnlyFilesUploadUnlessDirectionIsDownload() {
        assertEquals(SyncAction.UPLOAD, chooseSyncAction("upload", 20, 10_000, null, null))
        assertEquals(SyncAction.UPLOAD, chooseSyncAction("two-way", 20, 10_000, null, null))
        assertEquals(SyncAction.NONE, chooseSyncAction("download", 20, 10_000, null, null))
    }

    @Test fun serverOnlyFilesDownloadUnlessDirectionIsUpload() {
        assertEquals(SyncAction.DOWNLOAD, chooseSyncAction("download", null, null, 20, 10))
        assertEquals(SyncAction.DOWNLOAD, chooseSyncAction("two-way", null, null, 20, 10))
        assertEquals(SyncAction.NONE, chooseSyncAction("upload", null, null, 20, 10))
    }

    @Test fun twoWayKeepsTheNewerVersionAndReportsAmbiguousSizeConflicts() {
        assertEquals(SyncAction.UPLOAD, chooseSyncAction("two-way", 24, 20_000, 20, 10))
        assertEquals(SyncAction.DOWNLOAD, chooseSyncAction("two-way", 20, 10_000, 24, 20))
        assertEquals(SyncAction.CONFLICT, chooseSyncAction("two-way", 24, 10_000, 20, 10))
        assertEquals(SyncAction.UPLOAD, chooseSyncAction("upload", 24, 10_000, 20, 10))
        assertEquals(SyncAction.DOWNLOAD, chooseSyncAction("download", 24, 10_000, 20, 10))
    }

    @Test fun matchingFilesAreLeftAlone() {
        assertEquals(SyncAction.NONE, chooseSyncAction("two-way", 24, 10_000, 24, 10))
    }
}
