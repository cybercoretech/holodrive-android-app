package dev.cybercore.holodrive.photos

import java.util.Locale

internal fun combinePhotoFolders(contents: List<List<PhotoEntry>>): List<PhotoEntry> =
    contents.flatten().distinctBy { it.path }

internal fun sortLibraryPhotos(entries: List<PhotoEntry>, order: String): List<PhotoEntry> = when (order) {
    "oldest" -> entries.sortedWith { first, second ->
        comparePhotoDate(first, second, newestFirst = false).takeIf { it != 0 }
            ?: first.name.lowercase(Locale.ROOT).compareTo(second.name.lowercase(Locale.ROOT))
    }
    "name" -> entries.sortedWith(compareBy<PhotoEntry> { it.name.lowercase(Locale.ROOT) }.thenByDescending { it.modified })
    else -> entries.sortedWith { first, second ->
        comparePhotoDate(first, second, newestFirst = true).takeIf { it != 0 }
            ?: first.name.lowercase(Locale.ROOT).compareTo(second.name.lowercase(Locale.ROOT))
    }
}

private fun comparePhotoDate(first: PhotoEntry, second: PhotoEntry, newestFirst: Boolean): Int = when {
    first.captureDate != null && second.captureDate != null ->
        if (newestFirst) second.captureDate.compareTo(first.captureDate) else first.captureDate.compareTo(second.captureDate)
    first.captureDate != null -> -1
    second.captureDate != null -> 1
    newestFirst -> second.modified.compareTo(first.modified)
    else -> first.modified.compareTo(second.modified)
}

internal fun sortFileEntries(entries: List<PhotoEntry>, order: String): List<PhotoEntry> = entries.sortedWith(
    compareBy<PhotoEntry> { !it.folder }.thenComparator { first, second ->
        when (order) {
            "newest" -> second.modified.compareTo(first.modified).takeIf { it != 0 }
            "oldest" -> first.modified.compareTo(second.modified).takeIf { it != 0 }
            "type" -> first.mime.substringBefore('/').compareTo(second.mime.substringBefore('/')).takeIf { it != 0 }
            else -> null
        } ?: first.name.compareTo(second.name, ignoreCase = true)
    },
)
