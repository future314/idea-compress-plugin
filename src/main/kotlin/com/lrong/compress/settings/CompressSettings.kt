package com.lrong.compress.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level settings store.
 *
 * `@Service` registers it automatically, so no `applicationService` entry is
 * needed (or allowed) in plugin.xml.
 */
@Service(Service.Level.APP)
@State(name = "FileCompressorSettings", storages = [Storage("fileCompressor.xml")])
class CompressSettings : PersistentStateComponent<CompressSettingsState> {

    private var state = CompressSettingsState()

    override fun getState(): CompressSettingsState = state

    override fun loadState(state: CompressSettingsState) {
        this.state = state
    }

    fun patterns(): List<String> = state.patterns.toList()

    fun setPatterns(patterns: List<String>) {
        state.patterns = patterns.toMutableList()
    }

    fun restoreDefaults() {
        state.patterns = CompressSettingsState.DEFAULT_PATTERNS.toMutableList()
    }

    companion object {
        val instance: CompressSettings
            get() = service()

        /** Snapshot of the current exclusion patterns, for the compression core. */
        fun currentPatterns(): List<String> = instance.patterns()
    }
}
