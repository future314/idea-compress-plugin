package com.lrong.compress

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * Balloon shown when an archive has been written (plan section 5).
 *
 * Offers "Show in Explorer", which is what the user actually wants to do next.
 */
object CompressNotifier {

    /** Must match the `<notificationGroup id="...">` in plugin.xml. */
    private const val GROUP_ID = "com.lrong.compress"

    fun notifySuccess(project: Project?, output: Path, result: ZipWriter.Result) {
        val size = humanSize(runCatching { Files.size(output) }.getOrDefault(0L))
        val content = buildString {
            append(CompressBundle.message("compress.notification.done", output.fileName.toString(), size, result.fileCount))
            if (result.skippedCount > 0) {
                append(" ")
                append(CompressBundle.message("compress.notification.skipped", result.skippedCount))
            }
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                CompressBundle.message("compress.notification.title"),
                content,
                NotificationType.INFORMATION,
            )

        if (RevealFileAction.isSupported()) {
            notification.addAction(
                NotificationAction.createSimpleExpiring(
                    CompressBundle.message("compress.notification.show"),
                ) {
                    RevealFileAction.openFile(output)
                },
            )
        }

        notify(project, notification)
    }

    fun notifyFailure(project: Project?, output: Path, error: Throwable) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                CompressBundle.message("compress.notification.title"),
                CompressBundle.message(
                    "compress.notification.failed",
                    output.fileName.toString(),
                    error.localizedMessage ?: error.javaClass.simpleName,
                ),
                NotificationType.ERROR,
            )
        notify(project, notification)
    }

    private fun notify(project: Project?, notification: Notification) {
        Notifications.Bus.notify(notification, project)
    }

    private fun humanSize(bytes: Long): String = StringUtil.formatFileSize(bytes)
}
