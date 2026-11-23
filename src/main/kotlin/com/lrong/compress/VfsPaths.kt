package com.lrong.compress

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Bridge between the VFS the IDE hands us and the `java.nio` paths the
 * compression core wants.
 *
 * Anything not backed by the local filesystem (a file inside a JAR, a remote
 * or in-memory file) has no `Path` to stream from, so it is rejected here and
 * the action hides itself rather than failing halfway through.
 */
object VfsPaths {

    /** `null` when the file cannot be streamed from disk. */
    fun toPath(file: VirtualFile): Path? {
        if (!file.isInLocalFileSystem) return null
        return runCatching { Paths.get(file.path) }.getOrNull()
    }

    /** Every selected file must be streamable from disk. */
    fun isCompressible(files: Array<VirtualFile>): Boolean =
        files.isNotEmpty() && files.all { it.isValid && toPath(it) != null }
}
