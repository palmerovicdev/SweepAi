package dev.sweep.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Preferences → Sweep → Custom Prompts entry. Same panel that used to be a tab
 * inside the custom modal — the "Save Changes" button inside the panel already
 * flushes to `SweepConfig` state, so this Configurable's lifecycle hooks stay
 * inert.
 */
class SweepCustomPromptsConfigurable(
    private val project: Project,
) : Configurable {
    private var component: JPanel? = null

    override fun createComponent(): JComponent {
        if (SweepSettings.getInstance().autocompleteOnlyMode) {
            return JBLabel(
                "Custom prompts are hidden while autocomplete-only mode is enabled. " +
                    "Disable it under Sweep Autocomplete settings to restore chat and agent features.",
            )
        }
        val panel = component ?: SweepConfig.getInstance(project).createCustomPromptsPanel()
        component = panel
        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() { /* panel commits via its own Save button */ }

    override fun reset() { /* panel reflects current state on rebuild */ }

    override fun getDisplayName(): String = "Custom Prompts"

    override fun disposeUIResources() {
        component = null
    }
}
