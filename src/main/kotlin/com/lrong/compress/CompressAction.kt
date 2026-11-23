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
        e.presentation.text = if (files.size == 1) {
            val stem = ArchiveNaming.baseNameFor(files[0].name, files[0].isDirectory)
            CompressBundle.message("compress.action.text", "$stem.zip")
        } else {
            CompressBundle.message("compress.action.text.many", files.size)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val files = selectedFiles(e) ?: return
        val paths = files.mapNotNull { VfsPaths.toPath(it) }
        if (paths.isEmpty()) return

        // ZipWriter streams from disk. Flush unsaved editor buffers first so the
        // archive contains what the user is looking at, not the last save.
        FileDocumentManager.getInstance().saveAllDocuments()

        val first: Path = paths.first()
        val baseDir = first.toAbsolutePath().parent ?: return
        val baseName = ArchiveNaming.baseNameFor(files[0].name, files[0].isDirectory)
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

    /**
     * `VIRTUAL_FILE_ARRAY` covers the Project view including multi-select; the
     * editor tab popup only supplies `VIRTUAL_FILE`.
     */
    private fun selectedFiles(e: AnActionEvent): Array<VirtualFile>? =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() }
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
}
