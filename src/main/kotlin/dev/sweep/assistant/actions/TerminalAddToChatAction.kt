package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindowManager
import dev.sweep.assistant.components.ChatComponent
import dev.sweep.assistant.theme.SweepIcons
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.TerminalSelectionUtils
import dev.sweep.assistant.utils.appendSelectionToChat
import dev.sweep.assistant.utils.isValidSelection

class TerminalAddToChatAction : AnAction() {
    private val logger = Logger.getInstance(TerminalAddToChatAction::class.java)

    init {
        templatePresentation.apply {
            icon = SweepIcons.Sweep16x16
            text = "Add to Sweep Chat"
            description = "Add the selected terminal text to the Sweep chat"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedText = TerminalSelectionUtils.resolveTerminalSelectedText(e, project)
        if (!isValidSelection(selectedText)) return

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
        if (TerminalSelectionUtils.isTerminalPopupPlace(e.place)) {
            e.presentation.isEnabledAndVisible = true
            return
        }
        e.presentation.isEnabledAndVisible =
            isValidSelection(TerminalSelectionUtils.resolveTerminalSelectedText(e, project))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
