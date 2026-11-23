package com.lrong.compress.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.lrong.compress.CompressBundle
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * `Settings | Tools | File Compressor` (plan section 8).
 *
 * A plain [Configurable] over an editable pattern list. Patterns are edited in
 * the dialog and only written back on Apply, so Cancel is honest.
 */
class CompressConfigurable : Configurable {

    private val model = DefaultListModel<String>()
    private var panel: JComponent? = null

    override fun getDisplayName(): String = CompressBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            visibleRowCount = 10
        }

        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val input = Messages.showInputDialog(
                    CompressBundle.message("settings.add.prompt"),
                    CompressBundle.message("settings.add.title"),
                    null,
                )?.trim()
                if (!input.isNullOrEmpty()) {
                    model.addElement(input)
                }
            }
            .setRemoveAction {
                // Remove back to front so the remaining indices stay valid.
                list.selectedIndices.sortedDescending().forEach { model.removeElementAt(it) }
            }
            .createPanel()

        val result = panel {
            row {
                label(CompressBundle.message("settings.patterns.label"))
            }
            row {
                cell(decorated).align(AlignX.FILL)
            }.resizableRow()
            row {
                button(CompressBundle.message("settings.restoreDefaults")) {
                    setPatterns(CompressSettingsState.DEFAULT_PATTERNS)
                }
            }
            row {
                comment(CompressBundle.message("settings.patterns.help"))
            }
        }
        panel = result
        return result
    }

    override fun isModified(): Boolean = currentPatterns() != CompressSettings.currentPatterns()

    override fun apply() {
        CompressSettings.instance.setPatterns(currentPatterns())
    }

    override fun reset() {
        setPatterns(CompressSettings.currentPatterns())
    }

    override fun disposeUIResources() {
        panel = null
        model.clear()
    }

    private fun currentPatterns(): List<String> =
        (0 until model.size()).map { model.getElementAt(it) }

    private fun setPatterns(patterns: List<String>) {
        model.clear()
        patterns.forEach { model.addElement(it) }
    }
}
