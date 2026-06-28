package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

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
    private val exclusionsField =
        JBTextArea().apply {
            lineWrap = false
            rows = 7
            emptyText.text = "One file name or path pattern per line"
        }

    private var component: JPanel? = null

    override fun createComponent(): JComponent {
        if (component == null) {
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
                    .addLabeledComponent("Port:", portField)
                    .addSeparator()
                    .addLabeledComponent("Excluded files and paths:", exclusionsScroll)
                    .addComponentFillVertically(JPanel(), 0)
                    .panel
        }
        reset()
        return component!!
    }

    override fun isModified(): Boolean =
        enabledField.isSelected != settings.nextEditPredictionFlagOn ||
            localModeField.isSelected != settings.autocompleteLocalMode ||
            acceptWordField.isSelected != settings.acceptWordOnRightArrow ||
            showBadgeField.isSelected != config.isShowAutocompleteBadge() ||
            disableConflictsField.isSelected != config.isDisableConflictingPluginsEnabled() ||
            debounceField.intValue() != config.getDebounceThresholdMs().toInt() ||
            portField.intValue() != config.getAutocompleteLocalPort() ||
            exclusionPatterns() != config.getAutocompleteExclusionPatterns()

    override fun apply() {
        val wasLocalMode = settings.autocompleteLocalMode
        val oldPort = settings.autocompleteLocalPort

        settings.nextEditPredictionFlagOn = enabledField.isSelected
        settings.acceptWordOnRightArrow = acceptWordField.isSelected
        config.updateShowAutocompleteBadge(showBadgeField.isSelected)
        config.updateIsDisableConflictingPluginsEnabled(disableConflictsField.isSelected)
        config.updateDebounceThresholdMs(debounceField.intValue().toLong())
        config.updateAutocompleteExclusionPatterns(exclusionPatterns())
        config.updateAutocompleteLocalPort(portField.intValue())
        config.updateAutocompleteLocalMode(localModeField.isSelected)
        settings.notifySettingsChanged()

        if (localModeField.isSelected && (!wasLocalMode || oldPort != portField.intValue())) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val manager = LocalAutocompleteServerManager.getInstance()
                if (wasLocalMode && oldPort != portField.intValue()) {
                    manager.restartServer()
                } else {
                    manager.ensureServerRunning()
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
        exclusionsField.text = config.getAutocompleteExclusionPatterns().sorted().joinToString("\n")
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
