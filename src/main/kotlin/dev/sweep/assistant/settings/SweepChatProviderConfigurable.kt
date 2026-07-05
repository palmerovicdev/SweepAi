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
import dev.sweep.assistant.api.external.bridge.InstallProgress
import dev.sweep.assistant.api.external.bridge.NodeBridgeClient
import dev.sweep.assistant.api.external.bridge.NodeDetector
import dev.sweep.assistant.api.external.bridge.SdkManager
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
        ProviderChoice("local", "Local model"),
        ProviderChoice("opencode", "OpenCode"),
        ProviderChoice("codex", "Codex"),
    )

private const val OPENCODE_ID = "opencode"
private const val CODEX_ID = "codex"
private val CODEX_THINKING_MODES = arrayOf("", "shown", "hidden")

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
    private val opencodeStatusLabel = JBLabel(" ")

    // Codex fields
    private val codexCommandField = JBTextField().apply { columns = 32 }
    private val codexThinkingCombo = JComboBox(CODEX_THINKING_MODES)
    private val codexStatusLabel = JBLabel(" ")

    private val opencodePanel = JPanel()
    private val codexPanel = JPanel()

    // Node.js runtime + SDK management (bridge)
    private val nodePathField = JBTextField().apply {
        columns = 32
        emptyText.text = "Leave empty to auto-detect (node on PATH)"
    }
    private val bridgeStatusLabel = JBLabel(" ")
    private val codexSdkVersionField = JBTextField().apply { columns = 12 }
    private val opencodeSdkVersionField = JBTextField().apply { columns = 12 }
    private val codexInstalledLabel = JBLabel(" ")
    private val opencodeInstalledLabel = JBLabel(" ")
    private val installLog = javax.swing.JTextArea(6, 60).apply {
        isEditable = false
        lineWrap = false
        font = font.deriveFont(11f)
    }

    private var component: JPanel? = null

    override fun createComponent(): JComponent {
        if (SweepSettings.getInstance().autocompleteOnlyMode) {
            return JBLabel(
                "Chat provider settings are hidden while autocomplete-only mode is enabled. " +
                    "Disable it under Sweep Autocomplete settings to restore chat and agent features.",
            )
        }
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
                    .addSeparator()
                    .addComponent(buildBridgePanel())
                    .addComponentFillVertically(JPanel(), 0)
                    .panel
        }
        reset()
        return component!!
    }

    /**
     * Node.js runtime + SDK dependencies. Groups everything the ai-bridge
     * sidecar needs so users don't have to leave this tab to update Codex /
     * OpenCode SDK versions.
     */
    private fun buildBridgePanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.emptyLeft(4)

        panel.add(JBLabel("Node.js runtime (ai-bridge)"))
        panel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        panel.add(nodeRuntimeRow())
        panel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        panel.add(labeledRow("Bridge status:", bridgeStatusLabel))
        panel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        panel.add(bridgeButtonsRow())
        panel.add(javax.swing.Box.createRigidArea(Dimension(0, 8)))

        panel.add(JBLabel("SDK dependencies"))
        panel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        panel.add(sdkRow("@openai/codex-sdk", codexSdkVersionField, codexInstalledLabel))
        panel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        panel.add(sdkRow("@opencode-ai/sdk", opencodeSdkVersionField, opencodeInstalledLabel))
        panel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        panel.add(sdkButtonsRow())
        panel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        panel.add(javax.swing.JScrollPane(installLog))

        return panel
    }

    private fun nodeRuntimeRow(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(JBLabel("Node.js executable:"))
        add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
        add(nodePathField)
        add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
        add(JButton("Detect").apply { addActionListener { detectNode() } })
        add(javax.swing.Box.createRigidArea(Dimension(4, 0)))
        add(JButton("Test").apply { addActionListener { testBridge() } })
        add(javax.swing.Box.createHorizontalGlue())
    }

    private fun bridgeButtonsRow(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(JButton("Restart bridge").apply { addActionListener { restartBridge() } })
        add(javax.swing.Box.createHorizontalGlue())
    }

    private fun sdkRow(pkg: String, versionField: JBTextField, installedLabel: JBLabel): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(JBLabel(pkg))
        add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
        add(JBLabel("Wanted:"))
        add(javax.swing.Box.createRigidArea(Dimension(4, 0)))
        add(versionField)
        add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
        add(JBLabel("Installed:"))
        add(javax.swing.Box.createRigidArea(Dimension(4, 0)))
        add(installedLabel)
        add(javax.swing.Box.createHorizontalGlue())
    }

    private fun sdkButtonsRow(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(JButton("Update all").apply { addActionListener { runInstall(reinstall = false) } })
        add(javax.swing.Box.createRigidArea(Dimension(4, 0)))
        add(JButton("Reinstall from scratch").apply { addActionListener { runInstall(reinstall = true) } })
        add(javax.swing.Box.createHorizontalGlue())
    }

    private fun detectNode() {
        val detected = NodeDetector.detect(null)
        if (detected != null) {
            nodePathField.text = detected
            bridgeStatusLabel.text = "Found node at $detected"
            bridgeStatusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
        } else {
            bridgeStatusLabel.text = "Node.js not found — install Node 18+ (nodejs.org)."
            bridgeStatusLabel.foreground = JBColor.RED
        }
    }

    private fun testBridge() {
        bridgeStatusLabel.text = "Pinging bridge…"
        bridgeStatusLabel.foreground = JBColor.GRAY
        try {
            apply()
        } catch (_: Exception) {
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = try {
                runBlocking {
                    withTimeout(15_000L) {
                        val v = NodeBridgeClient.getInstance().ping()
                        val sdks = v["sdks"] as? kotlinx.serialization.json.JsonObject
                        val codex = sdks?.get("codex")?.toString() ?: "\"unknown\""
                        val opencode = sdks?.get("opencode")?.toString() ?: "\"unknown\""
                        "Bridge ready · codex=$codex opencode=$opencode"
                    }
                }
            } catch (e: Exception) {
                "Test failed: ${e.message ?: e.javaClass.simpleName}"
            }
            ApplicationManager.getApplication().invokeLater {
                bridgeStatusLabel.text = message
                bridgeStatusLabel.foreground = if (message.startsWith("Bridge ready")) {
                    JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
                } else {
                    JBColor.RED
                }
                refreshInstalledLabels()
            }
        }
    }

    private fun restartBridge() {
        bridgeStatusLabel.text = "Restarting…"
        bridgeStatusLabel.foreground = JBColor.GRAY
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                NodeBridgeClient.getInstance().restart()
                ApplicationManager.getApplication().invokeLater {
                    bridgeStatusLabel.text = "Bridge restarted"
                    bridgeStatusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
                }
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    bridgeStatusLabel.text = "Restart failed: ${e.message}"
                    bridgeStatusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    private fun runInstall(reinstall: Boolean) {
        try {
            apply()
        } catch (_: Exception) {
        }
        installLog.text = ""
        val append = { line: String ->
            ApplicationManager.getApplication().invokeLater {
                installLog.append(line + "\n")
                installLog.caretPosition = installLog.document.length
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val cb: (InstallProgress) -> Unit = { p ->
                when (p) {
                    is InstallProgress.Line -> append("[${p.stream}] ${p.text}")
                    is InstallProgress.Done -> append(if (p.ok) "✔ ${p.summary}" else "✘ ${p.summary}")
                }
            }
            if (reinstall) SdkManager.getInstance().reinstallAll(cb)
            else SdkManager.getInstance().updateAll(cb)
            ApplicationManager.getApplication().invokeLater { refreshInstalledLabels() }
        }
    }

    private fun refreshInstalledLabels() {
        val sdk = SdkManager.getInstance()
        codexInstalledLabel.text = sdk.getCodexSdkVersion() ?: "not installed"
        opencodeInstalledLabel.text = sdk.getOpencodeSdkVersion() ?: "not installed"
    }

    private fun buildOpencodePanel() {
        opencodePanel.layout = BoxLayout(opencodePanel, BoxLayout.Y_AXIS)
        opencodePanel.border = JBUI.Borders.emptyLeft(4)

        opencodePanel.add(JBLabel("OpenCode"))
        opencodePanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        opencodePanel.add(labeledRowWithButtons("Executable:", opencodeCommandField, OPENCODE_ID))
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

    override fun isModified(): Boolean {
        val selected = (providerCombo.selectedItem as ProviderChoice).id
        return selected != config.getChatProviderId() ||
            opencodeCommandField.text.trim() != config.getOpencodeCommand() ||
            codexCommandField.text.trim() != config.getCodexCommand() ||
            (codexThinkingCombo.selectedItem as String) != config.getCodexThinking() ||
            nodePathField.text.trim() != config.getAiBridgeNodePath() ||
            codexSdkVersionField.text.trim() != config.getCodexSdkVersion() ||
            opencodeSdkVersionField.text.trim() != config.getOpencodeSdkVersion()
    }

    override fun apply() {
        val selected = (providerCombo.selectedItem as ProviderChoice).id
        config.updateChatProviderId(selected)
        config.updateOpencodeCommand(opencodeCommandField.text.trim())
        config.updateCodexCommand(codexCommandField.text.trim())
        config.updateCodexThinking(codexThinkingCombo.selectedItem as String)
        config.updateAiBridgeNodePath(nodePathField.text.trim())
        config.updateCodexSdkVersion(codexSdkVersionField.text.trim().ifBlank { "latest" })
        config.updateOpencodeSdkVersion(opencodeSdkVersionField.text.trim().ifBlank { "latest" })
    }

    override fun reset() {
        val current = config.getChatProviderId()
        providerCombo.selectedItem =
            PROVIDER_CHOICES.firstOrNull { it.id == current } ?: PROVIDER_CHOICES.first()
        opencodeCommandField.text = config.getOpencodeCommand()
        codexCommandField.text = config.getCodexCommand()
        codexThinkingCombo.selectedItem =
            config.getCodexThinking().takeIf { it in CODEX_THINKING_MODES } ?: ""
        nodePathField.text = config.getAiBridgeNodePath()
        codexSdkVersionField.text = config.getCodexSdkVersion().ifBlank { "latest" }
        opencodeSdkVersionField.text = config.getOpencodeSdkVersion().ifBlank { "latest" }
        opencodeStatusLabel.text = " "
        codexStatusLabel.text = " "
        bridgeStatusLabel.text = " "
        refreshInstalledLabels()
        updateProviderPanelVisibility()
    }

    override fun disposeUIResources() {
        component = null
    }

    override fun getDisplayName(): String = "Chat Provider"
}
