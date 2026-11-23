package com.lrong.compress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/**
 * Covers the compression core described in plan section 6: entry naming,
 * UTF-8 names, pruning, self-inclusion, duplicate entry names, symlinks,
 * cancellation and failure cleanup.
 */
class ZipWriterTest {

    // ---- entry naming ------------------------------------------------

    @Test
    fun emptyDirectoryProducesExactlyOneTrailingSlashEntry(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("empty"))
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        assertEquals(listOf("empty/"), entriesOf(out))
    }

    @Test
    fun nestedTreeUsesForwardSlashEntryNames(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("src"))
        write(root, "com/App.java", "class App {}")
        write(root, "com/util/Helper.java", "class Helper {}")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        // Sorted: Files.walkFileTree hands back directory entries in whatever
        // order the filesystem reports, which is not guaranteed.
        assertEquals(
            listOf("src/", "src/com/", "src/com/App.java", "src/com/util/", "src/com/util/Helper.java").sorted(),
            entriesOf(out).sorted(),
        )
    }

    @Test
    fun topLevelItemNameIsPreservedAsTheEntryPrefix(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("src"))
        write(root, "A.java", "a")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        assertTrue(entriesOf(out).contains("src/A.java"))
    }

    @Test
    fun multipleSelectedItemsMergeIntoOneArchive(@TempDir dir: Path) {
        val a = Files.createDirectories(dir.resolve("a"))
        write(a, "x.txt", "x")
        val b = Files.createFile(dir.resolve("README.md"))
        Files.writeString(b, "hi")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(a, b), out)

        assertEquals(listOf("a/", "a/x.txt", "README.md").sorted(), entriesOf(out).sorted())
    }

    // ---- content -----------------------------------------------------

    @Test
    fun fileContentsRoundTripByteForByte(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("data"))
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        Files.write(root.resolve("big.bin"), payload)
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        ZipFile(out.toFile(), Charsets.UTF_8).use { zip ->
            val entry = zip.getEntry("data/big.bin")
            assertEquals(payload.size.toLong(), entry.size)
            val actual = zip.getInputStream(entry).readBytes()
            assertTrue(payload.contentEquals(actual), "payload was corrupted in transit")
        }
    }

    @Test
    fun entriesCarryTheSourceModificationTime(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("d"))
        val f = root.resolve("a.txt")
        Files.writeString(f, "x")
        val mtime = System.currentTimeMillis() - 86_400_000L
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime))
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        ZipFile(out.toFile(), Charsets.UTF_8).use { zip ->
            // ZIP stores DOS time at 2-second resolution and local-UTC offsets
            // vary, so compare within tolerance rather than for exact equality.
            val stored = zip.getEntry("d/a.txt").time
            assertTrue(kotlin.math.abs(stored - mtime) <= 2000, "mtime not preserved: want $mtime got $stored")
        }
    }

    // ---- UTF-8 / Chinese names ---------------------------------------

    @Test
    fun chineseFileNamesRoundTripThroughUtf8(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("中文目录"))
        write(root, "测试文件.txt", "内容")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        assertEquals(listOf("中文目录/", "中文目录/测试文件.txt").sorted(), entriesOf(out).sorted())
        ZipFile(out.toFile(), Charsets.UTF_8).use { zip ->
            val body = zip.getInputStream(zip.getEntry("中文目录/测试文件.txt")).readBytes().decodeToString()
            assertEquals("内容", body)
        }
    }

    @Test
    fun utf8LanguageEncodingFlagIsSetSoWindowsExplorerCanReadTheNames(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("中文"))
        write(root, "文件.txt", "x")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        // General purpose bit flag of the first local file header, bit 11 (EFS).
        // Without it Windows Explorer decodes entry names with the system
        // codepage and shows mojibake for Chinese names (plan risk 3).
        val bytes = Files.readAllBytes(out)
        assertEquals(0x50, bytes[0].toInt())   // P
        assertEquals(0x4b, bytes[1].toInt())   // K
        val flags = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
        assertTrue(flags and (1 shl 11) != 0, "EFS bit 11 not set; Windows Explorer will show mojibake")
    }

    // ---- exclusions --------------------------------------------------

    @Test
    fun excludedDirectoriesArePrunedAlongWithTheirContents(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("proj"))
        write(root, "App.java", "a")
        write(root, "node_modules/pkg/index.js", "j")
        write(root, "target/classes/A.class", "c")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out, ExclusionRules(listOf("node_modules", "target")))

        val entries = entriesOf(out)
        assertTrue(entries.contains("proj/App.java"))
        assertFalse(entries.any { it.contains("node_modules") })
        assertFalse(entries.any { it.contains("target") })
    }

    @Test
    fun exclusionPatternsDoNotApplyToTheSelectedRootsThemselves(@TempDir dir: Path) {
        // Right-clicking `node_modules` deliberately asks for it to be packed.
        val root = Files.createDirectories(dir.resolve("node_modules"))
        write(root, "pkg/index.js", "j")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out, ExclusionRules(listOf("node_modules")))

        assertTrue(entriesOf(out).contains("node_modules/pkg/index.js"))
    }

    // ---- self inclusion ----------------------------------------------

    @Test
    fun outputArchiveIsNeverAnEntryOfItself(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("root"))
        write(root, "a.txt", "a")
        // Output deliberately lands INSIDE the tree being walked.
        val out = root.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        val entries = entriesOf(out)
        assertFalse(entries.contains("root/out.zip"), "archive contained itself: $entries")
        assertTrue(entries.contains("root/a.txt"))
    }

    @Test
    fun anItemThatIsTheOutputPathIsNotArchived(@TempDir dir: Path) {
        val a = write(dir, "a.txt", "a")
        val out = dir.resolve("out.zip")
        Files.writeString(out, "pre-existing")

        ZipWriter.write(listOf(a, out), out)

        assertFalse(entriesOf(out).contains("out.zip"))
    }

    // ---- duplicate entry names ---------------------------------------

    @Test
    fun duplicateTopLevelNamesAreDisambiguatedInsteadOfCrashing(@TempDir dir: Path) {
        // Selecting `a/src` and `b/src` would otherwise make
        // ZipOutputStream throw ZipException: duplicate entry.
        val one = Files.createDirectories(dir.resolve("a/src"))
        write(one, "One.java", "1")
        val two = Files.createDirectories(dir.resolve("b/src"))
        write(two, "Two.java", "2")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(one, two), out)

        assertEquals(
            listOf("src/", "src/One.java", "src (1)/", "src (1)/Two.java").sorted(),
            entriesOf(out).sorted(),
        )
    }

    @Test
    fun duplicatePrefixesDoNotCollideForFilesEither(@TempDir dir: Path) {
        val one = write(Files.createDirectories(dir.resolve("a")), "same.txt", "1")
        val two = write(Files.createDirectories(dir.resolve("b")), "same.txt", "2")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(one, two), out)

        assertEquals(listOf("same.txt", "same (1).txt").sorted(), entriesOf(out).sorted())
    }

    // ---- symlinks ----------------------------------------------------

    @Test
    fun symlinksAreSkippedRatherThanFollowed(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("root"))
        write(root, "real.txt", "r")
        val ok = try {
            Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("real.txt"))
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue(ok, "symlink creation not permitted on this machine")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        assertEquals(listOf("root/", "root/real.txt").sorted(), entriesOf(out).sorted())
    }

    @Test
    fun aSymlinkedDirectoryIsNotDescended(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("root"))
        write(root, "real/x.txt", "r")
        val ok = try {
            Files.createSymbolicLink(root.resolve("loop"), root)
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue(ok, "symlink creation not permitted on this machine")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root), out)

        val entries = entriesOf(out)
        assertTrue(entries.contains("root/real/x.txt"))
        assertFalse(entries.any { it.startsWith("root/loop") }, "symlinked dir was traversed: $entries")
    }

    @Test
    fun aSelectedSymlinkToADirectoryIsArchivedUnderItsOwnName(@TempDir dir: Path) {
        // Review finding 3: a selected root that is itself a symlink used to be
        // dropped, yielding an empty archive. Requires symlink privilege, so it
        // skips on Windows without Developer Mode / elevation.
        val real = Files.createDirectories(dir.resolve("real"))
        write(real, "inner/f.txt", "r")
        val link = dir.resolve("link")
        val ok = try {
            Files.createSymbolicLink(link, real)
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue(ok, "symlink creation not permitted on this machine")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(link), out)

        assertEquals(listOf("link/", "link/inner/", "link/inner/f.txt").sorted(), entriesOf(out).sorted())
    }

    @Test
    fun aSelectedSymlinkToAFileIsArchivedUnderItsOwnName(@TempDir dir: Path) {
        val real = write(dir, "real.txt", "payload")
        val link = dir.resolve("link.txt")
        val ok = try {
            Files.createSymbolicLink(link, real)
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue(ok, "symlink creation not permitted on this machine")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(link), out)

        assertEquals(listOf("link.txt"), entriesOf(out))
    }

    @Test
    fun aSelectedDirectoryJunctionIsArchivedUnderItsOwnName(@TempDir dir: Path) {
        // Windows junctions are creatable WITHOUT elevation (unlike symlinks)
        // and Java reports them as ordinary directories, so unlike the two tests
        // above this one actually runs here. It guards that root resolution does
        // not change the entry naming for the common Windows case.
        val real = Files.createDirectories(dir.resolve("real"))
        write(real, "inner/f.txt", "r")
        val junction = dir.resolve("link")
        val ok = createJunction(junction, real)
        assumeTrue(ok, "junction creation not permitted on this machine")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(junction), out)

        assertEquals(listOf("link/", "link/inner/", "link/inner/f.txt").sorted(), entriesOf(out).sorted())
    }

    // ---- cancellation and failure ------------------------------------

    @Test
    fun cancellationAbortsAndRemovesThePartialArchive(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("root"))
        for (i in 1..20) write(root, "f$i.txt", "x")
        val out = dir.resolve("out.zip")
        var seen = 0

        assertThrows(CancellationException::class.java) {
            ZipWriter.write(listOf(root), out, isCancelled = { ++seen > 3 })
        }

        assertFalse(Files.exists(out), "partial archive was left behind")
    }

    @Test
    fun aMissingRootIsCountedAsSkippedAndDoesNotAbort(@TempDir dir: Path) {
        val root = Files.createDirectories(dir.resolve("root"))
        write(root, "a.txt", "a")
        val missing = dir.resolve("nope")
        val out = dir.resolve("out.zip")

        val result = ZipWriter.write(listOf(root, missing), out)

        assertEquals(1, result.skippedCount)
        assertTrue(entriesOf(out).contains("root/a.txt"))
    }

    @Test
    fun exclusionPatternsDoNotApplyToSelectedFileRoots(@TempDir dir: Path) {
        // Right-clicking a .zip to re-pack it must not silently produce an empty
        // archive just because `*.zip` is in the DEFAULT exclusion list. Review
        // finding 1: visitFile lacked the root exemption that preVisitDirectory
        // has, so a selected FILE root was filtered out by its own pattern.
        val file = dir.resolve("data.zip")
        Files.writeString(file, "payload")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(file), out, ExclusionRules(listOf("*.zip")))

        assertTrue(entriesOf(out).contains("data.zip"), "selected file root was excluded by its own pattern")
    }

    @Test
    fun exclusionPatternsDoNotApplyToSelectedRootsInAMixedSelection(@TempDir dir: Path) {
        // `src/` + `module.iml` must both land in the archive even with the
        // default `*.iml` pattern, otherwise the user sends a silently
        // incomplete archive.
        val root = Files.createDirectories(dir.resolve("src"))
        write(root, "App.java", "a")
        val iml = dir.resolve("module.iml")
        Files.writeString(iml, "<module/>")
        val out = dir.resolve("out.zip")

        ZipWriter.write(listOf(root, iml), out, ExclusionRules(listOf("*.iml")))

        val entries = entriesOf(out)
        assertTrue(entries.contains("src/App.java"))
        assertTrue(entries.contains("module.iml"), "selected .iml root was dropped")
    }

    // ---- empty archives must fail loudly ------------------------------

    @Test
    fun writeFailsLoudlyWhenNothingCouldBeArchived(@TempDir dir: Path) {
        // Review finding 2: an unreadable or vanished selection used to produce
        // a valid-looking empty ZIP and a success balloon saying "0 files".
        val missing = dir.resolve("nope")
        val out = dir.resolve("out.zip")

        assertThrows(IllegalStateException::class.java) {
            ZipWriter.write(listOf(missing), out)
        }

        assertFalse(Files.exists(out), "an empty archive must not be left behind")
    }

    // ---- helpers -----------------------------------------------------

    private fun write(root: Path, relative: String, content: String): Path {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
        return target
    }

    private fun entriesOf(zip: Path): List<String> {
        ZipFile(zip.toFile(), Charsets.UTF_8).use { zf ->
            return zf.entries().toList().map { it.name }
        }
    }

    /** Windows-only: `mklink /J` needs no elevation, unlike a real symlink. */
    private fun createJunction(link: Path, target: Path): Boolean = try {
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        process.waitFor() == 0 && Files.exists(link)
    } catch (e: Exception) {
        false
    }
}
