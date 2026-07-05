package dev.sweep.assistant.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.api.external.bridge.NodeBridgeClient
import dev.sweep.assistant.api.external.bridge.OpencodeModel
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.withSweepFont
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import javax.swing.JPanel

/** Dynamically populated OpenCode model selector shown in the chat toolbar. */
class OpencodeModelPicker(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()),
    Disposable {
    private val comboBox = RoundedComboBox<ModelChoice>()
    private var choices: List<ModelChoice> = listOf(ModelChoice.Auto)
    private var loading = false
    private var loaded = false
    private var disposed = false
    private var updating = false
    private var wasVisible = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 6)
        comboBox.withSweepFont(project, scale = 1f)
        comboBox.isTransparent = true
        comboBox.setOptions(choices)
        comboBox.toolTipText = "OpenCode model"
        add(comboBox, BorderLayout.CENTER)

        comboBox.addActionListener {
            if (updating) return@addActionListener
            val selection = comboBox.selectedItem ?: return@addActionListener
            val value = selection.id
            if (value != SweepSettings.getInstance().opencodeModel) {
                SweepConfig.getInstance(project).updateOpencodeModel(value)
                SweepSettings.getInstance().notifySettingsChanged()
            }
        }

        Disposer.register(parentDisposable, this)
        refresh()
    }

    fun refresh() {
        val settings = SweepSettings.getInstance()
        val visible = settings.chatProviderId == "opencode"
        isVisible = visible
        selectStoredModel(settings.opencodeModel)
        if (visible && !wasVisible && !loaded) loadModels()
        wasVisible = visible
        revalidate()
        repaint()
    }

    private fun loadModels() {
        if (loading || disposed) return
        loading = true
        comboBox.toolTipText = "Loading OpenCode models…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result =
                runCatching {
                    runBlocking {
                        NodeBridgeClient.getInstance().listOpencodeModels(project.basePath.orEmpty())
                    }
                }
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                loading = false
                result.fold(
                    onSuccess = {
                        loaded = true
                        installModels(it)
                    },
                    onFailure = {
                        comboBox.toolTipText = "OpenCode models unavailable: ${it.message ?: "unknown error"}"
                    },
                )
            }
        }
    }

    private fun installModels(models: List<OpencodeModel>) {
        val dynamic =
            models
                .distinctBy { it.id }
                .map { ModelChoice.Model(it.id, it.providerName, it.name) }
        choices = listOf(ModelChoice.Auto) + dynamic
        updating = true
        try {
            comboBox.setOptions(choices)
            selectStoredModel(SweepSettings.getInstance().opencodeModel)
        } finally {
            updating = false
        }
        comboBox.toolTipText =
            if (dynamic.isEmpty()) "No connected OpenCode models found; using Auto" else "OpenCode model"
    }

    private fun selectStoredModel(stored: String) {
        var selection = choices.firstOrNull { it.id == stored }
        if (selection == null && stored.isNotBlank()) {
            selection = ModelChoice.Model(stored, "", stored)
            choices = choices + selection
            comboBox.setOptions(choices)
        }
        selection = selection ?: ModelChoice.Auto
        if (comboBox.selectedItem == selection) return
        updating = true
        try {
            comboBox.selectedItem = selection
        } finally {
            updating = false
        }
    }

    override fun dispose() {
        disposed = true
    }

    sealed class ModelChoice(val id: String) {
        data object Auto : ModelChoice("") {
            override fun toString(): String = "Auto"
        }

        class Model(
            id: String,
            private val providerName: String,
            private val modelName: String,
        ) : ModelChoice(id) {
            override fun toString(): String =
                if (providerName.isBlank()) modelName else "$providerName · $modelName"

            override fun equals(other: Any?): Boolean = other is Model && other.id == id

            override fun hashCode(): Int = id.hashCode()
        }
    }
}
