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
 * The pill that occupies the "mode" slot in the chat toolbar when an external
 * provider is selected. The current mode changes shape per provider:
 *   - Codex     → approval policy (`never | on-request | on-failure | untrusted`)
 *   - OpenCode  → agent profile (`build | plan | chat`)
 *
 * The pill is invisible when the chat provider is Sweep Cloud / Local — the
 * regular [ModePickerMenu] shows in its place then. This class does not touch
 * the global mode state; it only writes back into [SweepSettings].
 */
class AgentProviderModePicker(
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
        add(comboBox, BorderLayout.CENTER)

        comboBox.addActionListener {
            val selection = comboBox.selectedItem as? String ?: return@addActionListener
            val settings = SweepSettings.getInstance()
            when (settings.chatProviderId) {
                "codex" -> if (selection != settings.codexApprovalPolicy) {
                    SweepConfig.getInstance(project).updateCodexApprovalPolicy(selection)
                    settings.notifySettingsChanged()
                }
                "opencode" -> if (selection != settings.opencodeAgent) {
                    SweepConfig.getInstance(project).updateOpencodeAgent(selection)
                    settings.notifySettingsChanged()
                }
            }
        }

        Disposer.register(parentDisposable, this)
        refresh()
    }

    /** Repaint from settings — options and selection depend on chatProviderId. */
    fun refresh() {
        val settings = SweepSettings.getInstance()
        val pid = settings.chatProviderId
        when (pid) {
            "codex" -> {
                comboBox.setOptions(CODEX_APPROVAL_OPTIONS)
                comboBox.selectedItem = settings.codexApprovalPolicy.ifBlank { CODEX_APPROVAL_OPTIONS[1] }
                comboBox.toolTipText = "Codex approval policy"
                isVisible = true
            }
            "opencode" -> {
                comboBox.setOptions(OPENCODE_AGENT_OPTIONS)
                comboBox.selectedItem = settings.opencodeAgent.ifBlank { OPENCODE_AGENT_OPTIONS[0] }
                comboBox.toolTipText = "OpenCode agent profile"
                isVisible = true
            }
            else -> {
                isVisible = false
            }
        }
        revalidate()
        repaint()
    }

    override fun dispose() { /* no dedicated resources */ }

    companion object {
        private val CODEX_APPROVAL_OPTIONS = listOf("never", "on-request", "on-failure", "untrusted")
        private val OPENCODE_AGENT_OPTIONS = listOf("build", "plan", "chat")
    }
}
