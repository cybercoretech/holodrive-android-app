package dev.cybercore.holodrive.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoLibraryTest {
    @Test
    fun combinesMultipleFoldersWithoutDuplicatingOverlappingPaths() {
        val camera = photo("trip.jpg", "pictures/camera/trip.jpg", 30)
        val screenshot = photo("trip.jpg", "pictures/screenshots/trip.jpg", 20)

        val combined = combinePhotoFolders(
            listOf(
                listOf(camera, screenshot),
                listOf(camera),
            ),
        )

        assertEquals(listOf(camera, screenshot), combined)
    }

    @Test
    fun sortsPhotosByNewestOldestOrName() {
        val older = photo("banana.jpg", "pictures/banana.jpg", 10)
        val middle = photo("apple.jpg", "screenshots/apple.jpg", 20)
        val newer = photo("zebra.jpg", "camera/zebra.jpg", 30)
        val entries = listOf(older, middle, newer)

        assertEquals(listOf(newer, middle, older), sortLibraryPhotos(entries, "newest"))
        assertEquals(listOf(older, middle, newer), sortLibraryPhotos(entries, "oldest"))
        assertEquals(listOf(middle, older, newer), sortLibraryPhotos(entries, "name"))
    }

    private fun photo(name: String, path: String, modified: Long) =
        PhotoEntry(name, path, folder = false, image = true, mime = "image/jpeg", size = 1, modified = modified)
}
