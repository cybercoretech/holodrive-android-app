package dev.cybercore.holodrive.photos

import java.util.Locale

internal fun combinePhotoFolders(contents: List<List<PhotoEntry>>): List<PhotoEntry> =
    contents.flatten().distinctBy { it.path }

internal fun sortLibraryPhotos(entries: List<PhotoEntry>, order: String): List<PhotoEntry> = when (order) {
    "oldest" -> entries.sortedWith(compareBy<PhotoEntry> { it.modified }.thenBy { it.name.lowercase(Locale.ROOT) })
    "name" -> entries.sortedWith(compareBy<PhotoEntry> { it.name.lowercase(Locale.ROOT) }.thenByDescending { it.modified })
    else -> entries.sortedWith(compareByDescending<PhotoEntry> { it.modified }.thenBy { it.name.lowercase(Locale.ROOT) })
}
