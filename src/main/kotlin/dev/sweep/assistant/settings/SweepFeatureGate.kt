package dev.sweep.assistant.settings

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.utils.SweepConstants

object SweepFeatureGate {
    fun isAutocompleteOnlyMode(): Boolean = SweepSettings.getInstance().autocompleteOnlyMode

    /**
     * Hides the action when autocomplete-only mode is on.
     * @return true when the caller should skip further update logic
     */
    fun hideNonAutocompleteFeatures(e: AnActionEvent): Boolean {
        if (!isAutocompleteOnlyMode()) return false
        e.presentation.isEnabledAndVisible = false
        return true
    }

    fun applyToolWindowAvailability(project: Project) {
        if (project.isDisposed) return
        val toolWindow =
            ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
                ?: return
        toolWindow.isAvailable = !isAutocompleteOnlyMode()
        if (isAutocompleteOnlyMode() && toolWindow.isVisible) {
            toolWindow.hide(null)
        }
    }
}
