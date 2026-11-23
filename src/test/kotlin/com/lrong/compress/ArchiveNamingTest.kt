package com.lrong.compress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Naming rules (plan sections 2 and 6.5):
 *  - archive lives next to the selection, named after the first selected item
 *  - collisions increment: `name.zip`, `name (1).zip`, `name (2).zip`, ...
 *  - a candidate is unusable if it already exists on disk OR is one of the
 *    inputs being archived (this is the self-inclusion guard, plan section 6.4)
 */
class ArchiveNamingTest {

    // ---- baseNameFor -------------------------------------------------

    @Test
    fun `directory names are used verbatim`() {
        assertEquals("src", ArchiveNaming.baseNameFor("src", isDirectory = true))
    }

    @Test
    fun `directory names keep their own extension`() {
        assertEquals("backup.zip", ArchiveNaming.baseNameFor("backup.zip", isDirectory = true))
    }

    @Test
    fun `file extension is stripped`() {
        assertEquals("readme", ArchiveNaming.baseNameFor("readme.txt", isDirectory = false))
    }

    @Test
    fun `chinese file names round trip`() {
        assertEquals("报告", ArchiveNaming.baseNameFor("报告.docx", isDirectory = false))
    }

    @Test
    fun `only the last extension is stripped`() {
        assertEquals("archive.tar", ArchiveNaming.baseNameFor("archive.tar.gz", isDirectory = false))
    }

    @Test
    fun `dotfiles are not treated as having an extension`() {
        assertEquals(".gitignore", ArchiveNaming.baseNameFor(".gitignore", isDirectory = false))
    }

    @Test
    fun `a name that is only an extension falls back to itself`() {
        assertEquals(".txt", ArchiveNaming.baseNameFor(".txt", isDirectory = false))
    }

    @Test
    fun `extensionless file names are used verbatim`() {
        assertEquals("LICENSE", ArchiveNaming.baseNameFor("LICENSE", isDirectory = false))
    }

    @Test
    fun `a trailing bare dot counts as an empty extension and is stripped`() {
        // `a.` has an empty extension; stripping it gives `a`, so the archive
        // is `a.zip` -- the same name Windows and 7-Zip would produce.
        assertEquals("a", ArchiveNaming.baseNameFor("a.", isDirectory = false))
    }

    @Test
    fun `a file literally named with only a zip extension is still a dotfile`() {
        assertEquals(".zip", ArchiveNaming.baseNameFor(".zip", isDirectory = false))
    }

    // ---- baseNameFor over a whole selection ---------------------------

    @Test
    fun `several items sharing a parent are named after that parent`(@TempDir dir: Path) {
        val parent = Files.createDirectories(dir.resolve("my-app"))
        val selection = listOf(
            ArchiveNaming.Selected(parent.resolve("src"), isDirectory = true),
            ArchiveNaming.Selected(parent.resolve("docs"), isDirectory = true),
            ArchiveNaming.Selected(parent.resolve("README.md"), isDirectory = false),
        )

        assertEquals("my-app", ArchiveNaming.baseNameFor(selection))
    }

    @Test
    fun `the parent name is kept verbatim even when it looks like a file name`(@TempDir dir: Path) {
        // A directory called `my.app` archives as `my.app.zip`, not `my.zip`.
        val parent = Files.createDirectories(dir.resolve("my.app"))
        val selection = listOf(
            ArchiveNaming.Selected(parent.resolve("src"), isDirectory = true),
            ArchiveNaming.Selected(parent.resolve("docs"), isDirectory = true),
        )

        assertEquals("my.app", ArchiveNaming.baseNameFor(selection))
    }

    @Test
    fun `items with different parents fall back to the first item`(@TempDir dir: Path) {
        val a = Files.createDirectories(dir.resolve("a"))
        val b = Files.createDirectories(dir.resolve("b"))
        val selection = listOf(
            ArchiveNaming.Selected(a.resolve("src"), isDirectory = true),
            ArchiveNaming.Selected(b.resolve("docs"), isDirectory = true),
        )

        assertEquals("src", ArchiveNaming.baseNameFor(selection))
    }

    @Test
    fun `a single item is still named after itself`(@TempDir dir: Path) {
        val parent = Files.createDirectories(dir.resolve("my-app"))
        val selection = listOf(
            ArchiveNaming.Selected(parent.resolve("readme.txt"), isDirectory = false),
        )

        assertEquals("readme", ArchiveNaming.baseNameFor(selection))
    }

    @Test
    fun `an empty selection falls back to a neutral stem`() {
        assertEquals("archive", ArchiveNaming.baseNameFor(emptyList()))
    }

    // ---- resolve -----------------------------------------------------

    @Test
    fun `first candidate is the plain name when nothing conflicts`(@TempDir dir: Path) {
        assertEquals(dir.resolve("src.zip"), ArchiveNaming.resolve(dir, "src", emptySet()))
    }

    @Test
    fun `collision increments to one`(@TempDir dir: Path) {
        create(dir, "src.zip")
        assertEquals(dir.resolve("src (1).zip"), ArchiveNaming.resolve(dir, "src", emptySet()))
    }

    @Test
    fun `collision increments past several existing archives`(@TempDir dir: Path) {
        create(dir, "src.zip")
        create(dir, "src (1).zip")
        create(dir, "src (2).zip")
        assertEquals(dir.resolve("src (3).zip"), ArchiveNaming.resolve(dir, "src", emptySet()))
    }

    @Test
    fun `an input path is skipped even when it does not exist`(@TempDir dir: Path) {
        val wouldBeOutput = dir.resolve("src.zip")
        assertEquals(
            dir.resolve("src (1).zip"),
            ArchiveNaming.resolve(dir, "src", setOf(wouldBeOutput)),
        )
    }

    @Test
    fun `existing zip suffix is not doubled`(@TempDir dir: Path) {
        // Selecting a file named `report.zip` must not produce `report.zip.zip`.
        assertEquals(dir.resolve("report.zip"), ArchiveNaming.resolve(dir, "report.zip", emptySet()))
    }

    @Test
    fun `a base name of only the zip extension falls back to archive`(@TempDir dir: Path) {
        assertEquals(dir.resolve("archive.zip"), ArchiveNaming.resolve(dir, ".zip", emptySet()))
    }

    @Test
    fun `selecting a zip beside an archive of the same name cannot swallow itself`(@TempDir dir: Path) {
        // The exact self-inclusion scenario from plan section 6.4: the selected
        // input is `src.zip`, so the plain candidate would be the input itself.
        val input = dir.resolve("src.zip")
        assertEquals(
            dir.resolve("src (1).zip"),
            ArchiveNaming.resolve(dir, "src.zip", setOf(input)),
        )
    }

    @Test
    fun `inputs are compared as absolute normalized paths`(@TempDir dir: Path) {
        val input = dir.resolve("src.zip")
        val sameButRelative = dir.resolve(".").resolve("src.zip").normalize()
        assertEquals(
            dir.resolve("src (1).zip"),
            ArchiveNaming.resolve(dir, "src.zip", setOf(sameButRelative)),
        )
    }

    @Test
    fun `gives up instead of looping forever`(@TempDir dir: Path) {
        // Every candidate exists and every candidate is also an input: the
        // resolution can never succeed and must raise rather than spin.
        val inputs = mutableSetOf<Path>()
        for (i in 0..ArchiveNaming.MAX_CANDIDATES) {
            val name = if (i == 0) "src.zip" else "src ($i).zip"
            create(dir, name)
            inputs.add(dir.resolve(name))
        }
        assertThrows(IllegalStateException::class.java) {
            ArchiveNaming.resolve(dir, "src", inputs)
        }
    }

    // ---- helpers -----------------------------------------------------

    private fun create(dir: Path, name: String) {
        java.nio.file.Files.writeString(dir.resolve(name), "x")
    }
}
