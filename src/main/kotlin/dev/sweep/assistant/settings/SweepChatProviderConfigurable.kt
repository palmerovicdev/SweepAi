package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.api.external.ExternalAgentProviderRegistry
import dev.sweep.assistant.api.external.TestResult
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

private data class ProviderChoice(val id: String, val label: String) {
    override fun toString(): String = label
}

private val PROVIDER_CHOICES =
    listOf(
        ProviderChoice("sweep-cloud", "Sweep Cloud (default)"),
        ProviderChoice("local", "Local model"),
        ProviderChoice("opencode", "OpenCode"),
        ProviderChoice("codex", "Codex"),
    )

private const val OPENCODE_ID = "opencode"
private const val CODEX_ID = "codex"

private val OPENCODE_AGENTS = arrayOf("build", "plan", "chat")
private val CODEX_APPROVAL_POLICIES = arrayOf("never", "on-request", "on-failure", "untrusted")
private val CODEX_SANDBOXES = arrayOf("read-only", "workspace-write", "danger-full-access")

/**
 * Fase 1 (§13.2) — Chat Provider settings tab.
 *
 * Renders the provider selector plus provider-specific fields. Detect / Test
 * Connection buttons are wired to the [ExternalAgentProviderRegistry]; while
 * Fase 2/3 aren't merged the registry has no providers registered and both
 * buttons surface a clear "not yet available" message instead of failing
 * silently.
 */
class SweepChatProviderConfigurable(
    private val project: Project,
) : Configurable {
    private val config
        get() = SweepConfig.getInstance(project)

    private val providerCombo = JComboBox(PROVIDER_CHOICES.toTypedArray())

    // OpenCode fields
    private val opencodeCommandField = JBTextField().apply { columns = 32 }
    private val opencodeExtraArgsField = JBTextField().apply { columns = 32 }
    private val opencodeBaseUrlField =
        JBTextField().apply {
            columns = 32
            emptyText.text = "Leave empty to auto-start opencode serve"
        }
    private val opencodeAgentCombo = JComboBox(OPENCODE_AGENTS)
    private val opencodeStatusLabel = JBLabel(" ")

    // Codex fields
    private val codexCommandField = JBTextField().apply { columns = 32 }
    private val codexExtraArgsField = JBTextField().apply { columns = 32 }
    private val codexModelField =
        JBTextField().apply {
            columns = 32
            emptyText.text = "(default — uses codex's persisted preference)"
        }
    private val codexApprovalCombo = JComboBox(CODEX_APPROVAL_POLICIES)
    private val codexSandboxCombo = JComboBox(CODEX_SANDBOXES)
    private val codexStatusLabel = JBLabel(" ")

    private val opencodePanel = JPanel()
    private val codexPanel = JPanel()

    private var component: JPanel? = null

    override fun createComponent(): JComponent {
        if (component == null) {
            buildOpencodePanel()
            buildCodexPanel()

            providerCombo.addActionListener {
                updateProviderPanelVisibility()
            }

            component =
                FormBuilder
                    .createFormBuilder()
                    .addComponent(JBLabel("Route the Sweep chat to a different backend."))
                    .addLabeledComponent("Provider:", providerCombo)
                    .addSeparator()
                    .addComponent(opencodePanel)
                    .addComponent(codexPanel)
                    .addComponentFillVertically(JPanel(), 0)
                    .panel
        }
        reset()
        return component!!
    }

    private fun buildOpencodePanel() {
        opencodePanel.layout = BoxLayout(opencodePanel, BoxLayout.Y_AXIS)
        opencodePanel.border = JBUI.Borders.emptyLeft(4)

        opencodePanel.add(JBLabel("OpenCode"))
        opencodePanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        opencodePanel.add(labeledRowWithButtons("Executable:", opencodeCommandField, OPENCODE_ID))
        opencodePanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        opencodePanel.add(labeledRow("Extra args:", opencodeExtraArgsField))
        opencodePanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        opencodePanel.add(labeledRow("Base URL:", opencodeBaseUrlField))
        opencodePanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        opencodePanel.add(labeledRow("Agent profile:", opencodeAgentCombo))
        opencodePanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        opencodePanel.add(labeledRow("Status:", opencodeStatusLabel))
    }

    private fun buildCodexPanel() {
        codexPanel.layout = BoxLayout(codexPanel, BoxLayout.Y_AXIS)
        codexPanel.border = JBUI.Borders.emptyLeft(4)

        codexPanel.add(JBLabel("Codex"))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRowWithButtons("Executable:", codexCommandField, CODEX_ID))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Extra args:", codexExtraArgsField))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Model:", codexModelField))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Approval:", codexApprovalCombo))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Sandbox:", codexSandboxCombo))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Status:", codexStatusLabel))
    }

    private fun labeledRow(label: String, field: JComponent): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel(label))
            add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
            add(field)
            add(javax.swing.Box.createHorizontalGlue())
        }

    private fun labeledRowWithButtons(label: String, field: JComponent, providerId: String): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel(label))
            add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
            add(field)
            add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
            add(
                JButton("Detect").apply {
                    addActionListener { runDetect(providerId) }
                },
            )
            add(javax.swing.Box.createRigidArea(Dimension(4, 0)))
            add(
                JButton("Test Connection").apply {
                    addActionListener { runTestConnection(providerId) }
                },
            )
            add(javax.swing.Box.createHorizontalGlue())
        }

    private fun updateProviderPanelVisibility() {
        val selected = (providerCombo.selectedItem as ProviderChoice).id
        opencodePanel.isVisible = selected == OPENCODE_ID
        codexPanel.isVisible = selected == CODEX_ID
        component?.revalidate()
        component?.repaint()
    }

    private fun runDetect(providerId: String) {
        val registry = ExternalAgentProviderRegistry.getInstance()
        val provider = registry.resolve(providerId)
        val statusLabel = if (providerId == OPENCODE_ID) opencodeStatusLabel else codexStatusLabel
        val commandField = if (providerId == OPENCODE_ID) opencodeCommandField else codexCommandField
        if (provider == null) {
            statusLabel.text = "Provider not registered (unexpected — please report)"
            statusLabel.foreground = JBColor.GRAY
            return
        }
        val detected = provider.detectExecutable()
        if (detected != null) {
            commandField.text = detected
            statusLabel.text = "Found: $detected"
            statusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
        } else {
            statusLabel.text = "Executable not found in PATH"
            statusLabel.foreground = JBColor.RED
        }
    }

    private fun runTestConnection(providerId: String) {
        val statusLabel = if (providerId == OPENCODE_ID) opencodeStatusLabel else codexStatusLabel
        val registry = ExternalAgentProviderRegistry.getInstance()
        val provider = registry.resolve(providerId)
        if (provider == null) {
            statusLabel.text = "Provider not registered (unexpected — please report)"
            statusLabel.foreground = JBColor.GRAY
            return
        }

        // Persist the fields the user just typed before the test spawns a
        // subprocess against them — otherwise "Test" reads stale settings.
        try {
            apply()
        } catch (_: Exception) {
        }

        statusLabel.text = "Testing…"
        statusLabel.foreground = JBColor.GRAY

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                runBlocking {
                    val handle = provider.ensureRunning(project, SweepSettings.getInstance())
                    provider.testConnection(handle)
                }
            } catch (e: Exception) {
                TestResult.Fail(e.message ?: e.javaClass.simpleName)
            }
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is TestResult.Ok -> {
                        statusLabel.text = "Connected — ${result.details}"
                        statusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
                    }
                    is TestResult.Fail -> {
                        statusLabel.text = "Failed: ${result.reason.take(200)}"
                        statusLabel.foreground = JBColor.RED
                    }
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val selected = (providerCombo.selectedItem as ProviderChoice).id
        return selected != config.getChatProviderId() ||
            opencodeCommandField.text.trim() != config.getOpencodeCommand() ||
            opencodeExtraArgsField.text != config.getOpencodeExtraArgs() ||
            opencodeBaseUrlField.text.trim() != config.getOpencodeBaseUrl() ||
            (opencodeAgentCombo.selectedItem as String) != config.getOpencodeAgent() ||
            codexCommandField.text.trim() != config.getCodexCommand() ||
            codexExtraArgsField.text != config.getCodexExtraArgs() ||
            codexModelField.text.trim() != config.getCodexModel() ||
            (codexApprovalCombo.selectedItem as String) != config.getCodexApprovalPolicy() ||
            (codexSandboxCombo.selectedItem as String) != config.getCodexSandbox()
    }

    override fun apply() {
        val selected = (providerCombo.selectedItem as ProviderChoice).id
        config.updateChatProviderId(selected)
        config.updateOpencodeCommand(opencodeCommandField.text.trim())
        config.updateOpencodeExtraArgs(opencodeExtraArgsField.text)
        config.updateOpencodeBaseUrl(opencodeBaseUrlField.text.trim())
        config.updateOpencodeAgent(opencodeAgentCombo.selectedItem as String)
        config.updateCodexCommand(codexCommandField.text.trim())
        config.updateCodexExtraArgs(codexExtraArgsField.text)
        config.updateCodexModel(codexModelField.text.trim())
        config.updateCodexApprovalPolicy(codexApprovalCombo.selectedItem as String)
        config.updateCodexSandbox(codexSandboxCombo.selectedItem as String)
    }

    override fun reset() {
        val current = config.getChatProviderId()
        providerCombo.selectedItem =
            PROVIDER_CHOICES.firstOrNull { it.id == current } ?: PROVIDER_CHOICES.first()
        opencodeCommandField.text = config.getOpencodeCommand()
        opencodeExtraArgsField.text = config.getOpencodeExtraArgs()
        opencodeBaseUrlField.text = config.getOpencodeBaseUrl()
        opencodeAgentCombo.selectedItem = config.getOpencodeAgent().takeIf { it in OPENCODE_AGENTS } ?: "build"
        codexCommandField.text = config.getCodexCommand()
        codexExtraArgsField.text = config.getCodexExtraArgs()
        codexModelField.text = config.getCodexModel()
        codexApprovalCombo.selectedItem =
            config.getCodexApprovalPolicy().takeIf { it in CODEX_APPROVAL_POLICIES } ?: "on-request"
        codexSandboxCombo.selectedItem =
            config.getCodexSandbox().takeIf { it in CODEX_SANDBOXES } ?: "workspace-write"
        opencodeStatusLabel.text = " "
        codexStatusLabel.text = " "
        updateProviderPanelVisibility()
    }

    override fun disposeUIResources() {
        component = null
    }

    override fun getDisplayName(): String = "Chat Provider"
}
