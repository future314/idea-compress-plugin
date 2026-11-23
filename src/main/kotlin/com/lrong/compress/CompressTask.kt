package com.lrong.compress

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import java.util.concurrent.CancellationException

/**
 * Runs the compression off the EDT with a cancellable progress indicator
 * (plan sections 6.7 and 7).
 */
class CompressTask(
    project: Project?,
    private val items: List<Path>,
    private val output: Path,
    private val exclusions: ExclusionRules,
) : Task.Backgroundable(project, CompressBundle.message("compress.progress.title"), true) {

    private var result: ZipWriter.Result? = null

    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        indicator.text = CompressBundle.message("compress.progress.scanning")

        result = try {
            ZipWriter.write(
                items = items,
                output = output,
                exclusions = exclusions,
                onProgress = { done, total, current ->
                    if (total > 0) {
                        indicator.isIndeterminate = false
                        indicator.fraction = done.toDouble() / total
                    }
                    indicator.text2 = current
                },
                isCancelled = { indicator.isCanceled },
            )
        } catch (c: CancellationException) {
            // ZipWriter raises its own CancellationException; the platform wants
            // ProcessCanceledException so the task is reported as cancelled
            // rather than failed.
            throw ProcessCanceledException()
        }
    }

    override fun onSuccess() {
        // The archive was written behind the VFS's back; refresh so it appears
        // in the Project view without the user pressing anything.
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(output.toFile())
        CompressNotifier.notifySuccess(project, output, result ?: return)
    }

    override fun onThrowable(error: Throwable) {
        CompressNotifier.notifyFailure(project, output, error)
    }

    override fun onCancel() {
        // ZipWriter already deleted the partial archive; say nothing.
    }
}
