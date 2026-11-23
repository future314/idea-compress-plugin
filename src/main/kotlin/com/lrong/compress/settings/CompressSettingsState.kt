package com.lrong.compress.settings

/**
 * Persisted settings (plan section 8).
 *
 * A plain class with a no-arg constructor and mutable properties: that is what
 * the platform's XML serialiser needs to round-trip it into
 * `fileCompressor.xml`.
 */
class CompressSettingsState {

    var patterns: MutableList<String> = DEFAULT_PATTERNS.toMutableList()

    companion object {
        /**
         * Directories and files that are noise when you are zipping something up
         * to send to someone: VCS metadata, IDE config, build output,
         * dependency caches and OS litter.
         *
         * Deliberately NOT in here: `bin`, `lib`, `resources` -- those are often
         * exactly what the user wants to ship.
         */
        val DEFAULT_PATTERNS: List<String> = listOf(
            ".git",
            ".svn",
            ".hg",
            ".idea",
            ".vscode",
            ".claude",
            ".gradle",
            ".mvn",
            "target",
            "build",
            "out",
            "dist",
            "node_modules",
            "__pycache__",
            ".venv",
            "venv",
            "*.iml",
            "*.class",
            ".DS_Store",
            "Thumbs.db",
            "desktop.ini",
            "*.zip",
        )
    }
}
