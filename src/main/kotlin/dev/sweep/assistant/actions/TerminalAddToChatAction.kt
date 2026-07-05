package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.controllers.TerminalManagerService
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.appendSelectionToChat
import dev.sweep.assistant.utils.isValidSelection

class TerminalAddToChatAction : AnAction() {
    private val logger = Logger.getInstance(TerminalAddToChatAction::class.java)

    init {
        templatePresentation.apply {
            icon = SweepIcons.Sweep16x16
            text = "Add to Sweep"
            description = "Add the selected terminal text to the Sweep chat"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedText = getSelectedText(e, project) ?: return

        ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)?.let { toolWindow ->
            if (!toolWindow.isVisible) toolWindow.show()
        }

        appendSelectionToChat(project, selectedText, "TerminalOutput", logger)
        ChatComponent.getInstance(project).requestFocus()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = isValidSelection(getSelectedText(e, project))
    }

    private fun getSelectedText(
        e: AnActionEvent,
        project: Project,
    ): String? {
        e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.let {
            if (it.isNotBlank()) return it
        }
        return TerminalManagerService.getInstance(project).activeTerminal?.selectedText
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
