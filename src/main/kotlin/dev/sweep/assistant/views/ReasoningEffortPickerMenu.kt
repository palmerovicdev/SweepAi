package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.withSweepFont
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Small pill that toggles Codex's reasoning effort (`minimal | low | medium |
 * high`). Only rendered when [SweepSettings.chatProviderId] == "codex"; other
 * providers hide it. Writes back to [SweepSettings.codexReasoningEffort] and
 * fires [SweepSettings.notifySettingsChanged] so the next Codex send picks it
 * up without restarting the bridge.
 */
class ReasoningEffortPickerMenu(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()),
    Disposable {

    private val comboBox = RoundedComboBox<String>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 6)
        comboBox.withSweepFont(project, scale = 1f)
        comboBox.isTransparent = true
        comboBox.setOptions(OPTIONS)
        comboBox.toolTipText = "Codex reasoning effort"
        add(comboBox, BorderLayout.CENTER)

        comboBox.addActionListener {
            val selection = comboBox.selectedItem as? String ?: return@addActionListener
            val current = SweepSettings.getInstance().codexReasoningEffort
            if (selection != current) {
                SweepConfig.getInstance(project).updateCodexReasoningEffort(selection)
                SweepSettings.getInstance().notifySettingsChanged()
            }
        }

        Disposer.register(parentDisposable, this)
        refresh()
    }

    /** Sync display and visibility from [SweepSettings]. */
    fun refresh() {
        val settings = SweepSettings.getInstance()
        val effort = settings.codexReasoningEffort.ifBlank { DEFAULT }
        if (comboBox.selectedItem != effort) comboBox.selectedItem = effort
        isVisible = settings.chatProviderId == "codex"
        revalidate()
        repaint()
    }

    override fun dispose() { /* no dedicated resources */ }

    companion object {
        private val OPTIONS = listOf("minimal", "low", "medium", "high")
        private const val DEFAULT = "medium"
    }
}
