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
import kotlinx.coroutines.withTimeout
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

// Empty first entry means "use Codex's persisted default" (from ~/.codex/config.toml).
// The combo is editable so users can also type a custom model id.
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
private val CODEX_REASONING_EFFORTS =
    arrayOf("", "minimal", "low", "medium", "high")
private val CODEX_THINKING_MODES =
    arrayOf("", "shown", "hidden")

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

    // Model is a dropdown so users can pick a known Codex model instead of
    // typing a raw id; still editable so unusual / new model ids are accepted.
    private val codexModelCombo =
        JComboBox(CODEX_MODELS).apply {
            isEditable = true
        }
    private val codexApprovalCombo = JComboBox(CODEX_APPROVAL_POLICIES)
    private val codexSandboxCombo = JComboBox(CODEX_SANDBOXES)
    private val codexReasoningEffortCombo = JComboBox(CODEX_REASONING_EFFORTS)
    private val codexThinkingCombo = JComboBox(CODEX_THINKING_MODES)
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
        codexPanel.add(labeledRow("Model:", codexModelCombo))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Approval:", codexApprovalCombo))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Sandbox:", codexSandboxCombo))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Reasoning effort:", codexReasoningEffortCombo))
        codexPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        codexPanel.add(labeledRow("Thinking:", codexThinkingCombo))
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
                    // Hard outer bound so the label never gets stuck at "Testing…"
                    // even if a subprocess never exits or an HTTP request never
                    // returns; per-step timeouts inside the providers are shorter.
                    withTimeout(TEST_CONNECTION_TIMEOUT_MS) {
                        val handle = provider.ensureRunning(project, SweepSettings.getInstance())
                        provider.testConnection(handle)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                TestResult.Fail(
                    "Timed out after ${TEST_CONNECTION_TIMEOUT_MS / 1000}s. " +
                        "Check that the executable is on PATH and can start manually.",
                )
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

    companion object {
        private const val TEST_CONNECTION_TIMEOUT_MS: Long = 45_000L
    }

    private fun codexModelValue(): String =
        (codexModelCombo.editor?.item?.toString() ?: (codexModelCombo.selectedItem as? String).orEmpty()).trim()

    override fun isModified(): Boolean {
        val selected = (providerCombo.selectedItem as ProviderChoice).id
        return selected != config.getChatProviderId() ||
            opencodeCommandField.text.trim() != config.getOpencodeCommand() ||
            opencodeExtraArgsField.text != config.getOpencodeExtraArgs() ||
            opencodeBaseUrlField.text.trim() != config.getOpencodeBaseUrl() ||
            (opencodeAgentCombo.selectedItem as String) != config.getOpencodeAgent() ||
            codexCommandField.text.trim() != config.getCodexCommand() ||
            codexExtraArgsField.text != config.getCodexExtraArgs() ||
            codexModelValue() != config.getCodexModel() ||
            (codexApprovalCombo.selectedItem as String) != config.getCodexApprovalPolicy() ||
            (codexSandboxCombo.selectedItem as String) != config.getCodexSandbox() ||
            (codexReasoningEffortCombo.selectedItem as String) != config.getCodexReasoningEffort() ||
            (codexThinkingCombo.selectedItem as String) != config.getCodexThinking()
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
        config.updateCodexModel(codexModelValue())
        config.updateCodexApprovalPolicy(codexApprovalCombo.selectedItem as String)
        config.updateCodexSandbox(codexSandboxCombo.selectedItem as String)
        config.updateCodexReasoningEffort(codexReasoningEffortCombo.selectedItem as String)
        config.updateCodexThinking(codexThinkingCombo.selectedItem as String)
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
        val storedModel = config.getCodexModel()
        codexModelCombo.selectedItem = if (storedModel in CODEX_MODELS) storedModel else storedModel
        codexApprovalCombo.selectedItem =
            config.getCodexApprovalPolicy().takeIf { it in CODEX_APPROVAL_POLICIES } ?: "on-request"
        codexSandboxCombo.selectedItem =
            config.getCodexSandbox().takeIf { it in CODEX_SANDBOXES } ?: "workspace-write"
        codexReasoningEffortCombo.selectedItem =
            config.getCodexReasoningEffort().takeIf { it in CODEX_REASONING_EFFORTS } ?: ""
        codexThinkingCombo.selectedItem =
            config.getCodexThinking().takeIf { it in CODEX_THINKING_MODES } ?: ""
        opencodeStatusLabel.text = " "
        codexStatusLabel.text = " "
        updateProviderPanelVisibility()
    }

    override fun disposeUIResources() {
        component = null
    }

    override fun getDisplayName(): String = "Chat Provider"
}
