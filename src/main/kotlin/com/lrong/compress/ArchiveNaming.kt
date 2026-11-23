package com.lrong.compress

import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure naming logic for the output archive (plan sections 2 and 6.5).
 *
 * Deliberately free of IntelliJ API so it can be unit-tested without a running
 * IDE. The rules:
 *
 *  - the archive sits next to the selection and is named after the first
 *    selected item, with the item's file extension removed
 *    (`readme.md` -> `readme.zip`, a directory `src` -> `src.zip`)
 *  - collisions increment: `name.zip`, `name (1).zip`, `name (2).zip`, ...
 *  - a candidate is unusable when it already exists on disk **or** is one of
 *    the inputs being archived. That second condition is the self-inclusion
 *    guard (plan section 6.4): selecting `src/` together with a pre-existing
 *    `src.zip` beside it would otherwise name the output `src.zip` and put the
 *    archive inside itself.
 */
object ArchiveNaming {

    /** Upper bound on candidate names before giving up, so we never spin. */
    const val MAX_CANDIDATES: Int = 1000

    private const val EXTENSION = ".zip"
    private const val FALLBACK_STEM = "archive"

    /**
     * The stem of the archive name for an item called [itemName].
     *
     * Directories keep their name verbatim, including any extension
     * (`backup.zip` is a perfectly ordinary directory name). For files the last
     * extension is dropped, matching what Windows "Send to compressed folder",
     * 7-Zip and WinRAR all do. A leading dot is a dotfile, not an extension, so
     * `.gitignore` and `.txt` come back unchanged.
     */
    fun baseNameFor(itemName: String, isDirectory: Boolean): String {
        if (isDirectory) return itemName
        val lastDot = itemName.lastIndexOf('.')
        if (lastDot <= 0) return itemName
        val stripped = itemName.substring(0, lastDot)
        return stripped.ifBlank { itemName }
    }

    /**
     * Resolve a non-conflicting archive path in [baseDir] for an archive named
     * after [baseName], avoiding anything in [inputs].
     *
     * [inputs] may hold relative or non-normalised paths; they are compared
     * after normalisation. Throws [IllegalStateException] if no candidate in
     * `[0, MAX_CANDIDATES]` is free.
     */
    fun resolve(baseDir: Path, baseName: String, inputs: Set<Path>): Path {
        val stem = stemOf(baseName)
        val blocked = inputs.mapTo(HashSet()) { it.toAbsolutePath().normalize() }

        for (i in 0..MAX_CANDIDATES) {
            val name = if (i == 0) "$stem$EXTENSION" else "$stem ($i)$EXTENSION"
            val candidate = baseDir.resolve(name)
            if (Files.exists(candidate)) continue
            if (candidate.toAbsolutePath().normalize() in blocked) continue
            return candidate
        }
        throw IllegalStateException(
            "No free archive name for '$baseName' in $baseDir " +
                "(all ${MAX_CANDIDATES + 1} candidates are taken)",
        )
    }

    private fun stemOf(baseName: String): String {
        if (baseName.endsWith(EXTENSION, ignoreCase = true)) {
            val without = baseName.substring(0, baseName.length - EXTENSION.length)
            // A file literally called ".zip" strips to nothing; fall back rather
            // than emit ".zip.zip".
            return without.ifBlank { FALLBACK_STEM }
        }
        return baseName.ifBlank { FALLBACK_STEM }
    }
}
