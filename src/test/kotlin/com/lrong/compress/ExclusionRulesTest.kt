package com.lrong.compress

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Matching rules (plan section 8):
 *  1. a pattern containing `/` or `**` is a glob matched against the path
 *     relative to the archive root, with `/` separators
 *  2. a plain pattern matches any single file or directory NAME
 *  3. excluded directories are pruned by the walker, so this class is only
 *     ever asked about paths it can answer with a name or a path match
 */
class ExclusionRulesTest {

    // ---- plain name patterns -----------------------------------------

    @Test
    fun `plain pattern matches a bare name`() {
        val rules = ExclusionRules(listOf("target"))
        assertTrue(rules.isExcluded("target"))
    }

    @Test
    fun `plain pattern matches a nested name`() {
        val rules = ExclusionRules(listOf("target"))
        assertTrue(rules.isExcluded("src/main/target"))
    }

    @Test
    fun `plain pattern does not match a longer name`() {
        val rules = ExclusionRules(listOf("target"))
        assertFalse(rules.isExcluded("targeted"))
    }

    @Test
    fun `plain pattern with star matches by name`() {
        val rules = ExclusionRules(listOf("*.iml"))
        assertTrue(rules.isExcluded("module.iml"))
        assertTrue(rules.isExcluded("a/b/module.iml"))
        assertFalse(rules.isExcluded("module.imlx"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        val rules = ExclusionRules(listOf("a?c"))
        assertTrue(rules.isExcluded("abc"))
        assertFalse(rules.isExcluded("ac"))
        assertFalse(rules.isExcluded("abbc"))
    }

    @Test
    fun `plain pattern does not match a sibling segment`() {
        val rules = ExclusionRules(listOf("build"))
        assertFalse(rules.isExcluded("build.gradle"))
    }

    @Test
    fun `empty pattern list excludes nothing`() {
        val rules = ExclusionRules(emptyList())
        assertFalse(rules.isExcluded("node_modules"))
        assertFalse(rules.isExcluded("a/b/c.js"))
    }

    @Test
    fun `blank patterns are ignored`() {
        val rules = ExclusionRules(listOf("", "   "))
        assertFalse(rules.isExcluded("anything"))
    }

    // ---- path glob patterns ------------------------------------------

    @Test
    fun `slash pattern matches a rooted path`() {
        val rules = ExclusionRules(listOf("src/generated"))
        assertTrue(rules.isExcluded("src/generated"))
        assertFalse(rules.isExcluded("a/src/generated"))
    }

    @Test
    fun `double star crosses directory boundaries`() {
        val rules = ExclusionRules(listOf("docs/**/*.tmp"))
        assertTrue(rules.isExcluded("docs/a.tmp"))
        assertTrue(rules.isExcluded("docs/a/b/c.tmp"))
        assertFalse(rules.isExcluded("other/a.tmp"))
    }

    @Test
    fun `trailing double star also matches the directory itself`() {
        val rules = ExclusionRules(listOf("src/generated/**"))
        assertTrue(rules.isExcluded("src/generated"))
        assertTrue(rules.isExcluded("src/generated/x/y.java"))
    }

    @Test
    fun `single star does not cross directory boundaries`() {
        val rules = ExclusionRules(listOf("docs/*.tmp"))
        assertTrue(rules.isExcluded("docs/a.tmp"))
        assertFalse(rules.isExcluded("docs/a/b.tmp"))
    }

    @Test
    fun `leading slash is tolerated`() {
        val rules = ExclusionRules(listOf("/.idea"))
        assertTrue(rules.isExcluded(".idea"))
    }

    @Test
    fun `trailing slash is tolerated`() {
        val rules = ExclusionRules(listOf("node_modules/"))
        assertTrue(rules.isExcluded("node_modules"))
        assertTrue(rules.isExcluded("a/node_modules"))
    }

    @Test
    fun `backslash separators are normalised to forward slashes`() {
        val rules = ExclusionRules(listOf("src\\generated"))
        assertTrue(rules.isExcluded("src/generated"))
        assertTrue(rules.isExcluded("src\\generated"))
    }

    @Test
    fun `leading dot slash is tolerated`() {
        val rules = ExclusionRules(listOf("./build"))
        assertTrue(rules.isExcluded("build"))
    }

    // ---- multiple patterns -------------------------------------------

    @Test
    fun `any matching pattern excludes`() {
        val rules = ExclusionRules(listOf("target", "src/generated/**", "*.log"))
        assertTrue(rules.isExcluded("target"))
        assertTrue(rules.isExcluded("src/generated/x.java"))
        assertTrue(rules.isExcluded("a/b/trace.log"))
        assertFalse(rules.isExcluded("src/main/App.java"))
    }
}
