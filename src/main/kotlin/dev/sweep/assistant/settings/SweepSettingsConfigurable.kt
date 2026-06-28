package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import java.awt.Dimension
import java.awt.event.ActionListener
import java.net.URI
import javax.swing.ButtonGroup
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SweepSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()
    private val config
        get() = SweepConfig.getInstance(project)

    private val enabledField = JBCheckBox("Enable autocomplete")
    private val localModeField = JBCheckBox("Use local autocomplete server")
    private val acceptWordField = JBCheckBox("Accept the next word with Right Arrow")
    private val showBadgeField = JBCheckBox("Show the Tab-to-accept badge")
    private val disableConflictsField = JBCheckBox("Disable conflicting autocomplete plugins automatically")
    private val debounceField = JSpinner(SpinnerNumberModel(100, 10, 1000, 10))
    private val portField = JSpinner(SpinnerNumberModel(8081, 1, 65535, 1))

    private val managedRadio = JBRadioButton("Launch managed server", true)
    private val externalRadio = JBRadioButton("Connect to existing server", false)

    private val modelRepoField = JBTextField().apply {
        emptyText.text = "sweepai/sweep-next-edit-0.5B"
        columns = 32
    }
    private val modelFilenameField = JBTextField().apply {
        emptyText.text = "sweep-next-edit-0.5b.q8_0.gguf"
        columns = 32
    }
    private val externalUrlField = JBTextField().apply {
        emptyText.text = "http://localhost:1234"
        columns = 32
    }

    private val managedPanel = JPanel()
    private val externalPanel = JPanel()
    private val externalStatusLabel = JBLabel(" ")
    private val urlValidationLabel = JBLabel(" ")

    private val exclusionsField =
        JBTextArea().apply {
            lineWrap = false
            rows = 7
            emptyText.text = "One file name or path pattern per line"
        }

    private var component: JPanel? = null

    override fun createComponent(): JComponent {
        if (component == null) {
            val buttonGroup = ButtonGroup().apply {
                add(managedRadio)
                add(externalRadio)
            }
            // Ensure exactly one is selected even if defaults drift
            if (!managedRadio.isSelected && !externalRadio.isSelected) managedRadio.isSelected = true
            buttonGroup.toString() // silence unused warning

            buildManagedPanel()
            buildExternalPanel()

            val modeSwitchListener = ActionListener {
                updatePanelVisibility()
                updateLocalControlsEnabled()
            }
            managedRadio.addActionListener(modeSwitchListener)
            externalRadio.addActionListener(modeSwitchListener)
            localModeField.addActionListener(modeSwitchListener)

            externalUrlField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = refreshUrlValidation()
                override fun removeUpdate(e: DocumentEvent?) = refreshUrlValidation()
                override fun changedUpdate(e: DocumentEvent?) = refreshUrlValidation()
            })

            val exclusionsScroll =
                JBScrollPane(exclusionsField).apply {
                    preferredSize = Dimension(520, 140)
                }

            component =
                FormBuilder
                    .createFormBuilder()
                    .addComponent(JBLabel("Autocomplete"))
                    .addComponent(enabledField)
                    .addComponent(acceptWordField)
                    .addComponent(showBadgeField)
                    .addComponent(disableConflictsField)
                    .addLabeledComponent("Debounce (ms):", debounceField)
                    .addSeparator()
                    .addComponent(JBLabel("Local server"))
                    .addComponent(localModeField)
                    .addComponent(managedRadio)
                    .addComponent(externalRadio)
                    .addComponent(managedPanel)
                    .addComponent(externalPanel)
                    .addSeparator()
                    .addLabeledComponent("Excluded files and paths:", exclusionsScroll)
                    .addComponentFillVertically(JPanel(), 0)
                    .panel
        }
        reset()
        return component!!
    }

    private fun buildManagedPanel() {
        managedPanel.layout = BoxLayout(managedPanel, BoxLayout.Y_AXIS)
        managedPanel.border = JBUI.Borders.emptyLeft(20)

        val portRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Port:"))
            add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
            add(portField)
            add(javax.swing.Box.createHorizontalGlue())
        }
        val repoRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Model Repo:"))
            add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
            add(modelRepoField)
        }
        val filenameRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Model File:"))
            add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
            add(modelFilenameField)
        }
        managedPanel.add(portRow)
        managedPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        managedPanel.add(repoRow)
        managedPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        managedPanel.add(filenameRow)
    }

    private fun buildExternalPanel() {
        externalPanel.layout = BoxLayout(externalPanel, BoxLayout.Y_AXIS)
        externalPanel.border = JBUI.Borders.emptyLeft(20)

        val urlRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Server URL:"))
            add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
            add(externalUrlField)
        }
        val statusRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Status:"))
            add(javax.swing.Box.createRigidArea(Dimension(8, 0)))
            add(externalStatusLabel)
            add(javax.swing.Box.createRigidArea(Dimension(12, 0)))
            add(
                javax.swing.JButton("Test").apply {
                    addActionListener { runExternalHealthCheck() }
                },
            )
            add(javax.swing.Box.createHorizontalGlue())
        }
        urlValidationLabel.foreground = JBColor.RED

        externalPanel.add(urlRow)
        externalPanel.add(javax.swing.Box.createRigidArea(Dimension(0, 4)))
        externalPanel.add(statusRow)
        externalPanel.add(urlValidationLabel)
    }

    private fun updatePanelVisibility() {
        managedPanel.isVisible = managedRadio.isSelected
        externalPanel.isVisible = externalRadio.isSelected
        component?.revalidate()
        component?.repaint()
    }

    private fun updateLocalControlsEnabled() {
        val localOn = localModeField.isSelected
        managedRadio.isEnabled = localOn
        externalRadio.isEnabled = localOn
        setPanelEnabled(managedPanel, localOn && managedRadio.isSelected)
        setPanelEnabled(externalPanel, localOn && externalRadio.isSelected)
    }

    private fun setPanelEnabled(panel: JPanel, enabled: Boolean) {
        panel.isEnabled = enabled
        for (c in panel.components) {
            c.isEnabled = enabled
            if (c is JPanel) setPanelEnabled(c, enabled)
        }
    }

    private fun isValidExternalUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val uri = URI.create(trimmed)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshUrlValidation() {
        val text = externalUrlField.text
        if (text.isBlank() || isValidExternalUrl(text)) {
            urlValidationLabel.text = " "
        } else {
            urlValidationLabel.text = "Invalid URL — expected http(s)://host[:port]"
        }
    }

    private fun runExternalHealthCheck() {
        val url = externalUrlField.text.trim()
        if (!isValidExternalUrl(url)) {
            externalStatusLabel.text = "Invalid URL"
            externalStatusLabel.foreground = JBColor.RED
            return
        }
        externalStatusLabel.text = "Checking..."
        externalStatusLabel.foreground = JBColor.GRAY
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = LocalAutocompleteServerManager.getInstance().isUrlHealthy(url)
            ApplicationManager.getApplication().invokeLater {
                if (ok) {
                    externalStatusLabel.text = "Reachable"
                    externalStatusLabel.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(80, 200, 80))
                } else {
                    externalStatusLabel.text = "Not reachable"
                    externalStatusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    private fun currentManagedSelected(): Boolean = managedRadio.isSelected

    override fun isModified(): Boolean {
        val externalUrlText = externalUrlField.text.trim()
        // Treat external mode as persisted-blank if user picked external but URL is invalid
        val externalUrlForCompare =
            if (currentManagedSelected()) "" else if (isValidExternalUrl(externalUrlText)) externalUrlText else ""

        return enabledField.isSelected != settings.nextEditPredictionFlagOn ||
            localModeField.isSelected != settings.autocompleteLocalMode ||
            acceptWordField.isSelected != settings.acceptWordOnRightArrow ||
            showBadgeField.isSelected != config.isShowAutocompleteBadge() ||
            disableConflictsField.isSelected != config.isDisableConflictingPluginsEnabled() ||
            debounceField.intValue() != config.getDebounceThresholdMs().toInt() ||
            portField.intValue() != config.getAutocompleteLocalPort() ||
            modelRepoField.text.trim() != config.getAutocompleteLocalModelRepo() ||
            modelFilenameField.text.trim() != config.getAutocompleteLocalModelFilename() ||
            externalUrlForCompare != config.getAutocompleteExternalUrl() ||
            exclusionPatterns() != config.getAutocompleteExclusionPatterns()
    }

    override fun apply() {
        val wasLocalMode = settings.autocompleteLocalMode
        val oldPort = settings.autocompleteLocalPort
        val oldExternalUrl = settings.autocompleteExternalUrl
        val oldRepo = settings.autocompleteLocalModelRepo
        val oldFilename = settings.autocompleteLocalModelFilename
        val wasManaged = oldExternalUrl.isBlank()

        settings.nextEditPredictionFlagOn = enabledField.isSelected
        settings.acceptWordOnRightArrow = acceptWordField.isSelected
        config.updateShowAutocompleteBadge(showBadgeField.isSelected)
        config.updateIsDisableConflictingPluginsEnabled(disableConflictsField.isSelected)
        config.updateDebounceThresholdMs(debounceField.intValue().toLong())
        config.updateAutocompleteExclusionPatterns(exclusionPatterns())
        config.updateAutocompleteLocalPort(portField.intValue())
        config.updateAutocompleteLocalMode(localModeField.isSelected)
        config.updateAutocompleteLocalModelRepo(modelRepoField.text.trim())
        config.updateAutocompleteLocalModelFilename(modelFilenameField.text.trim())

        val externalUrlText = externalUrlField.text.trim()
        val newExternalUrl =
            if (currentManagedSelected()) "" else if (isValidExternalUrl(externalUrlText)) externalUrlText else ""
        config.updateAutocompleteExternalUrl(newExternalUrl)

        settings.notifySettingsChanged()

        val isNowLocalMode = localModeField.isSelected
        val isNowManaged = newExternalUrl.isBlank()
        val newPort = portField.intValue()
        val newRepo = modelRepoField.text.trim()
        val newFilename = modelFilenameField.text.trim()

        val manager = LocalAutocompleteServerManager.getInstance()
        ApplicationManager.getApplication().executeOnPooledThread {
            when {
                !isNowLocalMode -> {
                    if (wasLocalMode && wasManaged) manager.stopManagedServer()
                }
                isNowManaged -> {
                    val switchedFromExternal = wasLocalMode && !wasManaged
                    val modelChanged = newRepo != oldRepo || newFilename != oldFilename
                    val portChanged = newPort != oldPort
                    if (!wasLocalMode || switchedFromExternal) {
                        manager.ensureServerRunning()
                    } else if (portChanged || modelChanged) {
                        manager.restartServer()
                    }
                }
                else -> {
                    if (wasLocalMode && wasManaged) manager.stopManagedServer()
                }
            }
        }
    }

    override fun reset() {
        enabledField.isSelected = settings.nextEditPredictionFlagOn
        localModeField.isSelected = settings.autocompleteLocalMode
        acceptWordField.isSelected = settings.acceptWordOnRightArrow
        showBadgeField.isSelected = config.isShowAutocompleteBadge()
        disableConflictsField.isSelected = config.isDisableConflictingPluginsEnabled()
        debounceField.value = config.getDebounceThresholdMs().toInt()
        portField.value = config.getAutocompleteLocalPort()
        modelRepoField.text = config.getAutocompleteLocalModelRepo()
        modelFilenameField.text = config.getAutocompleteLocalModelFilename()
        val savedUrl = config.getAutocompleteExternalUrl()
        externalUrlField.text = savedUrl
        if (savedUrl.isBlank()) {
            managedRadio.isSelected = true
        } else {
            externalRadio.isSelected = true
        }
        exclusionsField.text = config.getAutocompleteExclusionPatterns().sorted().joinToString("\n")
        externalStatusLabel.text = " "
        refreshUrlValidation()
        updatePanelVisibility()
        updateLocalControlsEnabled()
    }

    override fun disposeUIResources() {
        component = null
    }

    override fun getDisplayName(): String = "Sweep Autocomplete"

    private fun exclusionPatterns(): Set<String> =
        exclusionsField.text
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

    private fun JSpinner.intValue(): Int = (value as Number).toInt()
}
