package com.lrong.compress

import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The compression core (plan section 6).
 *
 * Free of IntelliJ API so the whole thing is unit-testable without a running
 * IDE. The action layer turns `VirtualFile`s into [Path]s and adapts the
 * progress indicator to [isCancelled].
 *
 * Guarantees:
 *  - entry names use `/` and keep the selected item's name as the top-level
 *    prefix, so right-clicking `src` yields `src/com/App.java`, not `com/App.java`
 *  - empty directories produce a trailing-slash entry of their own
 *  - entry names are written as UTF-8, which makes the JDK set the EFS flag so
 *    Windows Explorer and Linux `unzip` read Chinese names correctly
 *  - excluded directories are pruned from the walk, never merely filtered out
 *  - exclusion patterns are not applied to the selected roots themselves:
 *    deliberately right-clicking `node_modules` asks for it to be packed
 *  - the output archive is never an entry of itself
 *  - symlinks are skipped rather than followed, so a link cycle cannot hang us
 *  - any failure deletes the partial archive instead of leaving it behind
 */
object ZipWriter {

    private const val BUFFER_SIZE = 64 * 1024

    data class Result(
        val fileCount: Int,
        val dirCount: Int,
        val totalBytes: Long,
        val skippedCount: Int,
    )

    /** One selected item and the top-level entry name it will be archived under. */
    data class Source(val root: Path, val prefix: String)

    /**
     * Pair each item with a unique top-level entry name.
     *
     * Necessary because `ZipOutputStream.putNextEntry` throws
     * `ZipException: duplicate entry` for repeated names, so selecting `a/src`
     * and `b/src` would otherwise abort the whole task. Duplicates increment
     * as `src`, `src (1)`, ...
     */
    fun resolveSources(items: List<Path>): List<Source> {
        val used = HashSet<String>()
        return items.map { item ->
            val base = item.fileName?.toString().orEmpty().ifEmpty { "item" }
            var prefix = base
            var i = 1
            while (prefix in used) {
                prefix = numbered(base, i)
                i++
            }
            used += prefix
            Source(item, prefix)
        }
    }

    /**
     * Windows-style duplicate naming: the number goes before the extension, so
     * `same.txt` becomes `same (1).txt` and a directory `src` becomes `src (1)`.
     * Matches what Explorer's "copy" produces and keeps the extension readable.
     */
    private fun numbered(base: String, index: Int): String {
        val lastDot = base.lastIndexOf('.')
        return if (lastDot > 0) "${base.substring(0, lastDot)} ($index)${base.substring(lastDot)}"
        else "$base ($index)"
    }

    /**
     * Archive [items] into [output].
     *
     * [onProgress] is called after each file with (filesDone, filesTotal, entryName).
     * [isCancelled] is polled before each item and during streaming; when it
     * turns true a [CancellationException] is raised and the partial archive is
     * removed.
     */
    fun write(
        items: List<Path>,
        output: Path,
        exclusions: ExclusionRules = ExclusionRules(emptyList()),
        onProgress: (filesDone: Long, filesTotal: Long, currentEntry: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result {
        val target = output.toAbsolutePath().normalize()
        val sources = resolveSources(items)
        val plan = plan(sources, target, exclusions, isCancelled)

        var fileCount = 0
        var dirCount = 0
        var totalBytes = 0L
        var filesDone = 0L

        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(target)), Charsets.UTF_8).use { zip ->
                for (entry in plan.entries) {
                    if (isCancelled()) throw CancellationException("Compression cancelled")
                    val zipEntry = ZipEntry(entry.name).apply { time = entry.mtime }
                    zip.putNextEntry(zipEntry)
                    if (!entry.isDirectory) {
                        Files.newInputStream(entry.path).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                zip.write(buffer, 0, read)
                                totalBytes += read
                                if (isCancelled()) throw CancellationException("Compression cancelled")
                            }
                        }
                    }
                    zip.closeEntry()

                    if (entry.isDirectory) dirCount++ else {
                        fileCount++
                        filesDone++
                        onProgress(filesDone, plan.fileTotal, entry.name)
                    }
                }
            }
        } catch (t: Throwable) {
            // Never leave a half-written archive behind, whether the user
            // cancelled or the disk went wrong (plan sections 6.4 and 6.7).
            runCatching { Files.deleteIfExists(target) }
            throw t
        }

        return Result(fileCount, dirCount, totalBytes, plan.skipped)
    }

    // ---- planning ----------------------------------------------------

    private class Planned(val path: Path, val name: String, val isDirectory: Boolean, val mtime: Long)

    private class Plan(val entries: List<Planned>, val fileTotal: Long, val skipped: Int)

    private fun plan(
        sources: List<Source>,
        target: Path,
        exclusions: ExclusionRules,
        isCancelled: () -> Boolean,
    ): Plan {
        val entries = ArrayList<Planned>()
        var skipped = 0
        var fileTotal = 0L

        for (source in sources) {
            if (isCancelled()) throw CancellationException("Compression cancelled")
            try {
                Files.walkFileTree(source.root, object : SimpleFileVisitor<Path>() {

                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (isCancelled()) throw CancellationException("Compression cancelled")
                        if (isSamePath(dir, target)) return FileVisitResult.SKIP_SUBTREE
                        val isRoot = isSamePath(dir, source.root)
                        if (!isRoot && (attrs.isSymbolicLink || Files.isSymbolicLink(dir))) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        val name = entryNameOf(source.prefix, source.root, dir, isDirectory = true)
                        if (!isRoot && exclusions.isExcluded(name)) return FileVisitResult.SKIP_SUBTREE
                        entries += Planned(dir, name, isDirectory = true, mtime = attrs.lastModifiedTime().toMillis())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (isCancelled()) throw CancellationException("Compression cancelled")
                        if (isSamePath(file, target)) return FileVisitResult.CONTINUE
                        if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) {
                            skipped++
                            return FileVisitResult.CONTINUE
                        }
                        val name = entryNameOf(source.prefix, source.root, file, isDirectory = false)
                        if (exclusions.isExcluded(name)) return FileVisitResult.CONTINUE
                        entries += Planned(file, name, isDirectory = false, mtime = attrs.lastModifiedTime().toMillis())
                        fileTotal++
                        return FileVisitResult.CONTINUE
                    }

                    // Windows long paths and ACL problems must not abort the
                    // whole archive (plan risk 8).
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        skipped++
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (e: IOException) {
                skipped++
            }
        }

        return Plan(entries, fileTotal, skipped)
    }

    // ---- helpers -----------------------------------------------------

    /** `prefix` for the root itself, `prefix/rel` below it. `/` separators, `/` suffix for dirs. */
    private fun entryNameOf(prefix: String, root: Path, path: Path, isDirectory: Boolean): String {
        val relative = root.relativize(path).toString().replace('\\', '/')
        val base = if (relative.isEmpty()) prefix else "$prefix/$relative"
        return if (isDirectory && !base.endsWith("/")) "$base/" else base
    }

    private fun isSamePath(a: Path, b: Path): Boolean =
        a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
}
