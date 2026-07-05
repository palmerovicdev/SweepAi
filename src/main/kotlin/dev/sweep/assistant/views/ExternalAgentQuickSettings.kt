package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Compact chat-side quick-settings widget for the external agent providers
 * (OpenCode / Codex). Shows the active provider label and a gear that opens a
 * popup with dropdowns for Provider, Model, Approval, Sandbox, Reasoning effort
 * and Thinking. Mirrors the Settings → Sweep AI → Chat Provider tab so users
 * can flip these without leaving the chat.
 *
 * Visibility is driven by `SweepSettings.chatProviderId`: the widget hides when
 * the user is on Sweep Cloud / Local and appears only for the external agent
 * providers so it doesn't clutter the chat header otherwise.
 */
class ExternalAgentQuickSettings(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val providerLabel = JBLabel().apply {
        border = JBUI.Borders.emptyRight(4)
    }
    private val gearButton = JButton(AllIcons.General.GearPlain).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        toolTipText = "Chat provider quick settings"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(20, 20)
        margin = Insets(0, 0, 0, 0)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(4)
        add(providerLabel, BorderLayout.CENTER)
        add(gearButton, BorderLayout.EAST)

        gearButton.addActionListener { showPopup(gearButton) }

        // Clicking the label itself also opens the popup — the extra hit area
        // is nice because the gear is tiny and users often miss it.
        providerLabel.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    showPopup(providerLabel)
                }
            },
        )
        providerLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        refresh()
    }

    /** Repaint label + visibility from the current settings. Cheap to call. */
    fun refresh() {
        val id = SweepSettings.getInstance().chatProviderId
        val label = PROVIDER_LABELS[id] ?: id
        providerLabel.text = "Agent: $label"
        isVisible = id == "opencode" || id == "codex"
        revalidate()
        repaint()
    }

    private fun showPopup(anchor: Component) {
        val panel = buildPopupPanel()
        val popup =
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel)
                .setTitle("Chat Provider")
                .setResizable(false)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()
        popup.show(RelativePoint(anchor, java.awt.Point(0, anchor.height)))
    }

    private data class ProviderChoice(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private fun buildPopupPanel(): JPanel {
        val settings = SweepSettings.getInstance()
        val config = SweepConfig.getInstance(project)

        val providerChoices = PROVIDER_LABELS.map { ProviderChoice(it.key, it.value) }.toTypedArray()
        val providerCombo = JComboBox(providerChoices).apply {
            selectedItem =
                providerChoices.firstOrNull { it.id == settings.chatProviderId }
                    ?: providerChoices.first()
        }

        val modelCombo = JComboBox(CODEX_MODELS).apply {
            isEditable = true
            selectedItem = settings.codexModel
        }
        val approvalCombo = JComboBox(CODEX_APPROVAL).apply { selectedItem = settings.codexApprovalPolicy }
        val sandboxCombo = JComboBox(CODEX_SANDBOX).apply { selectedItem = settings.codexSandbox }
        val effortCombo = JComboBox(CODEX_EFFORT).apply { selectedItem = settings.codexReasoningEffort }
        val thinkingCombo = JComboBox(CODEX_THINKING).apply { selectedItem = settings.codexThinking }
        val opencodeAgentCombo = JComboBox(OPENCODE_AGENTS).apply { selectedItem = settings.opencodeAgent }

        val panel =
            JPanel(GridBagLayout()).apply {
                border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
            }
        val gbc =
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(3, 3, 3, 3)
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
            }

        fun row(label: String, component: Component) {
            gbc.gridx = 0
            gbc.weightx = 0.0
            panel.add(JLabel(label, SwingConstants.RIGHT), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            panel.add(component, gbc)
            gbc.gridy++
        }

        row("Provider:", providerCombo)
        // Show provider-specific rows below. We show all rows and users pick
        // what they need — hiding based on provider selection here would need
        // a wire-up listener and the popup UX doesn't need that ceremony.
        row("Model (Codex):", modelCombo)
        row("Approval (Codex):", approvalCombo)
        row("Sandbox (Codex):", sandboxCombo)
        row("Reasoning effort:", effortCombo)
        row("Thinking:", thinkingCombo)
        row("Agent (OpenCode):", opencodeAgentCombo)

        val applyButton = JButton("Apply")
        applyButton.addActionListener {
            val providerChoice = providerCombo.selectedItem as? ProviderChoice
            config.updateChatProviderId(providerChoice?.id ?: "sweep-cloud")
            config.updateCodexModel((modelCombo.editor?.item?.toString() ?: "").trim())
            config.updateCodexApprovalPolicy(approvalCombo.selectedItem as String)
            config.updateCodexSandbox(sandboxCombo.selectedItem as String)
            config.updateCodexReasoningEffort(effortCombo.selectedItem as String)
            config.updateCodexThinking(thinkingCombo.selectedItem as String)
            config.updateOpencodeAgent(opencodeAgentCombo.selectedItem as String)
            refresh()
            javax.swing.SwingUtilities.getWindowAncestor(applyButton)?.dispose()
        }

        val buttonRow =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.emptyTop(6)
                add(Box.createHorizontalGlue())
                add(applyButton)
            }
        gbc.gridx = 0
        gbc.gridwidth = 2
        panel.add(buttonRow, gbc)
        return panel
    }

    companion object {
        private val PROVIDER_LABELS: LinkedHashMap<String, String> =
            linkedMapOf(
                "sweep-cloud" to "Sweep Cloud",
                "local" to "Local model",
                "opencode" to "OpenCode",
                "codex" to "Codex",
            )

        private val CODEX_MODELS =
            arrayOf(
                "",
                "gpt-5-codex",
                "gpt-5.5",
                "gpt-5",
                "gpt-5-mini",
                "gpt-4.1",
                "o3",
                "o3-mini",
                "o4-mini",
            )
        private val CODEX_APPROVAL = arrayOf("never", "on-request", "on-failure", "untrusted")
        private val CODEX_SANDBOX = arrayOf("read-only", "workspace-write", "danger-full-access")
        private val CODEX_EFFORT = arrayOf("", "minimal", "low", "medium", "high")
        private val CODEX_THINKING = arrayOf("", "shown", "hidden")
        private val OPENCODE_AGENTS = arrayOf("build", "plan", "chat")
    }
}
