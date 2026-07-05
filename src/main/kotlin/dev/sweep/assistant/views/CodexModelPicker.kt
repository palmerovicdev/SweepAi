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

/** Codex model selector shown in the chat toolbar when Codex is the active provider. */
class CodexModelPicker(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()),
    Disposable {
    private val comboBox = RoundedComboBox<ModelChoice>()
    private var choices: List<ModelChoice> = buildChoices()
    private var updating = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 6)
        comboBox.withSweepFont(project, scale = 1f)
        comboBox.isTransparent = true
        comboBox.setOptions(choices)
        comboBox.toolTipText = "Codex model"
        add(comboBox, BorderLayout.CENTER)

        comboBox.addActionListener {
            if (updating) return@addActionListener
            val selection = comboBox.selectedItem ?: return@addActionListener
            val value = selection.id
            if (value != SweepSettings.getInstance().codexModel) {
                SweepConfig.getInstance(project).updateCodexModel(value)
                SweepSettings.getInstance().notifySettingsChanged()
            }
        }

        Disposer.register(parentDisposable, this)
        refresh()
    }

    fun refresh() {
        val settings = SweepSettings.getInstance()
        isVisible = settings.chatProviderId == "codex"
        selectStoredModel(settings.codexModel)
        revalidate()
        repaint()
    }

    private fun selectStoredModel(stored: String) {
        var selection = choices.firstOrNull { it.id == stored }
        if (selection == null && stored.isNotBlank()) {
            selection = ModelChoice.Custom(stored)
            choices = choices + selection
            comboBox.setOptions(choices)
        }
        selection = selection ?: ModelChoice.Default
        if (comboBox.selectedItem == selection) return
        updating = true
        try {
            comboBox.selectedItem = selection
        } finally {
            updating = false
        }
    }

    override fun dispose() { /* no dedicated resources */ }

    sealed class ModelChoice(val id: String) {
        data object Default : ModelChoice("") {
            override fun toString(): String = "Auto"
        }

        class Preset(
            id: String,
            private val label: String,
        ) : ModelChoice(id) {
            override fun toString(): String = label

            override fun equals(other: Any?): Boolean = other is Preset && other.id == id

            override fun hashCode(): Int = id.hashCode()
        }

        class Custom(
            id: String,
        ) : ModelChoice(id) {
            override fun toString(): String = id

            override fun equals(other: Any?): Boolean = other is Custom && other.id == id

            override fun hashCode(): Int = id.hashCode()
        }
    }

    companion object {
        private fun buildChoices(): List<ModelChoice> =
            listOf(ModelChoice.Default) +
                PRESET_MODELS.map { (id, label) -> ModelChoice.Preset(id, label) }

        private val PRESET_MODELS: List<Pair<String, String>> =
            listOf(
                "gpt-5.5" to "GPT-5.5",
                "gpt-5.4" to "GPT-5.4",
                "gpt-5.2-codex" to "GPT-5.2-Codex",
                "gpt-5.1-codex-max" to "GPT-5.1-Codex-Max",
                "gpt-5.4-mini" to "GPT-5.4-Mini",
                "gpt-5.3-codex" to "GPT-5.3-Codex",
                "gpt-5.3-codex-spark" to "GPT-5.3-Codex-Spark",
                "gpt-5.2" to "GPT-5.2",
                "gpt-5.1-codex-mini" to "GPT-5.1-Codex-Mini",
            )
    }
}
