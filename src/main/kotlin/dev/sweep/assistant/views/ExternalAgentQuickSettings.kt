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
 * Provider selector pill for the chat toolbar. Shows the current external
 * provider (Codex / OpenCode) and lets the user swap between them. Visible
 * only when [SweepSettings.chatProviderId] is one of the external providers;
 * hidden otherwise so the toolbar stays clean when using Sweep Cloud / Local.
 */
class ExternalAgentQuickSettings(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()),
    Disposable {

    private val comboBox = RoundedComboBox<ProviderChoice>()

    init {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(4)
        comboBox.withSweepFont(project, scale = 1f)
        comboBox.isTransparent = true
        comboBox.setOptions(PROVIDER_CHOICES)
        comboBox.toolTipText = "Chat provider"
        add(comboBox, BorderLayout.CENTER)

        val current = SweepSettings.getInstance().chatProviderId
        comboBox.selectedItem = PROVIDER_CHOICES.firstOrNull { it.id == current } ?: PROVIDER_CHOICES.first()

        comboBox.addActionListener {
            val selection = comboBox.selectedItem as? ProviderChoice ?: return@addActionListener
            if (selection.id != SweepSettings.getInstance().chatProviderId) {
                SweepConfig.getInstance(project).updateChatProviderId(selection.id)
                SweepSettings.getInstance().notifySettingsChanged()
            }
        }

        Disposer.register(parentDisposable, this)
        refresh()
    }

    /** Re-hydrates the pill from [SweepSettings]. Cheap; call on any settings change. */
    fun refresh() {
        val id = SweepSettings.getInstance().chatProviderId
        val choice = PROVIDER_CHOICES.firstOrNull { it.id == id } ?: PROVIDER_CHOICES.first()
        if (comboBox.selectedItem != choice) comboBox.selectedItem = choice
        isVisible = id == "opencode" || id == "codex"
        revalidate()
        repaint()
    }

    override fun dispose() { /* no dedicated resources */ }

    data class ProviderChoice(val id: String, val label: String) {
        override fun toString(): String = label
    }

    companion object {
        private val PROVIDER_CHOICES: List<ProviderChoice> =
            listOf(
                ProviderChoice("codex", "Codex"),
                ProviderChoice("opencode", "OpenCode"),
            )
    }
}
