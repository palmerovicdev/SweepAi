package dev.sweep.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Preferences → Sweep → BYOK entry. Wraps [SweepConfig.createBYOKPanel] so the
 * same Swing tree that used to live in the modal is embedded in IntelliJ's
 * standard settings dialog. The panel commits changes to state on interaction
 * (per-field listeners), so [isModified]/[apply]/[reset] are no-ops.
 */
class SweepByokConfigurable(
    private val project: Project,
) : Configurable {
    private var component: JPanel? = null

    override fun createComponent(): JComponent {
        if (SweepSettings.getInstance().autocompleteOnlyMode) {
            return JBLabel(
                "BYOK is hidden while autocomplete-only mode is enabled. " +
                    "Disable it under Sweep Autocomplete settings to restore chat and agent features.",
            )
        }
        val panel = component ?: SweepConfig.getInstance(project).createBYOKPanel()
        component = panel
        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() { /* panel commits inline */ }

    override fun reset() { /* panel reflects current state on rebuild */ }

    override fun getDisplayName(): String = "BYOK"

    override fun disposeUIResources() {
        component = null
    }
}
