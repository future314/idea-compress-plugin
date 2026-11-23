package com.lrong.compress

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.lrong.compress.settings.CompressSettings
import java.nio.file.Path

/**
 * Right-click "Compress to ..." in the Project view and on editor tabs
 * (plan section 7).
 *
 * `DumbAware` because zipping files needs no indices, so it stays usable while
 * the IDE is still indexing.
 */
class CompressAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = selectedFiles(e)
        if (files == null || !VfsPaths.isCompressible(files)) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = true
        // Cheap: the stem only, no collision scan, so a menu open never does I/O.
        val stem = ArchiveNaming.baseNameFor(selectionOf(files))
        e.presentation.text = CompressBundle.message("compress.action.text", "$stem.zip")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val files = selectedFiles(e) ?: return
        val selection = selectionOf(files)
        if (selection.isEmpty()) return
        val paths = selection.map { it.path }

        // ZipWriter streams from disk. Flush unsaved editor buffers first so the
        // archive contains what the user is looking at, not the last save.
        FileDocumentManager.getInstance().saveAllDocuments()

        val first: Path = paths.first()
        val baseDir = first.toAbsolutePath().parent ?: return
        val baseName = ArchiveNaming.baseNameFor(selection)
        val output = ArchiveNaming.resolve(baseDir, baseName, paths.toSet())

        ProgressManager.getInstance().run(
            CompressTask(
                project = project,
                items = paths,
                output = output,
                exclusions = ExclusionRules(CompressSettings.currentPatterns()),
            ),
        )
    }

    private fun selectionOf(files: Array<VirtualFile>): List<ArchiveNaming.Selected> =
        files.mapNotNull { file -> VfsPaths.toPath(file)?.let { ArchiveNaming.Selected(it, file.isDirectory) } }

    /**
     * `VIRTUAL_FILE_ARRAY` covers the Project view including multi-select; the
     * editor tab popup only supplies `VIRTUAL_FILE`.
     */
    private fun selectedFiles(e: AnActionEvent): Array<VirtualFile>? =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() }
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
}
