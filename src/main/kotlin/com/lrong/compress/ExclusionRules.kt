package com.lrong.compress

/**
 * Decides which paths stay out of the archive (plan section 8).
 *
 * Two kinds of pattern, matched against the entry path the user would see in
 * the archive (`/`-separated, prefix included, no leading or trailing slash):
 *
 *  1. a pattern with more than one segment, or containing a double-star
 *     segment, is a glob matched against the whole path. A double star crosses
 *     directory boundaries and matches zero or more segments, so `docs/` plus a
 *     double star matches `docs` itself as well as `docs/a/b`.
 *  2. a single-segment pattern matches any file or directory **name**, wherever
 *     it sits. So `target` excludes `target`, `src/main/target` and anything
 *     inside them (the walker prunes rather than filtering afterwards).
 *
 * Single-segment wildcards: `*` and `?` never cross `/`.
 *
 * Free of IntelliJ API on purpose: `FileUtil.wildcardMatch` is no longer
 * present in the 2025.1 platform, and a hand-rolled matcher is unit-testable
 * without a running IDE.
 */
class ExclusionRules(patterns: List<String>) {

    private val rules: List<Rule> = patterns.mapNotNull { compile(it) }

    /** [path] is an archive entry path, `/`- or `\`-separated. */
    fun isExcluded(path: String): Boolean {
        val segments = splitSegments(path)
        if (segments.isEmpty()) return false
        return rules.any { it.matches(segments) }
    }

    private sealed class Rule {
        abstract fun matches(segments: List<String>): Boolean
    }

    /** Matches the last path segment. */
    private class NameRule(private val regex: Regex) : Rule() {
        override fun matches(segments: List<String>): Boolean = regex.matches(segments.last())
    }

    /** Matches the whole path segment by segment. `null` part = `**`. */
    private class PathRule(private val parts: List<Regex?>) : Rule() {
        override fun matches(segments: List<String>): Boolean = match(segments, 0, 0)

        private fun match(segments: List<String>, pi: Int, si: Int): Boolean {
            if (pi == parts.size) return si == segments.size
            val part = parts[pi]
            if (part == null) {
                // `**` matches zero or more segments.
                for (k in si..segments.size) {
                    if (match(segments, pi + 1, k)) return true
                }
                return false
            }
            if (si == segments.size) return false
            if (!part.matches(segments[si])) return false
            return match(segments, pi + 1, si + 1)
        }
    }

    private fun compile(raw: String): Rule? {
        val segments = splitSegments(raw)
        if (segments.isEmpty()) return null
        if (segments.size == 1 && segments[0] != "**") return NameRule(segmentRegex(segments[0]))
        return PathRule(segments.map { if (it == "**") null else segmentRegex(it) })
    }

    private fun segmentRegex(segment: String): Regex {
        val body = StringBuilder()
        var i = 0
        while (i < segment.length) {
            when (segment[i]) {
                '*' -> body.append("[^/]*")
                '?' -> body.append("[^/]")
                else -> body.append(Regex.escape(segment[i].toString()))
            }
            i++
        }
        return Regex("^$body$")
    }

    private fun splitSegments(raw: String): List<String> =
        raw.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
}
