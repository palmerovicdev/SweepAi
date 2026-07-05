package dev.sweep.assistant.utils

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.terminal.JBTerminalWidget
import dev.sweep.assistant.controllers.TerminalManagerService

object TerminalSelectionUtils {
    private val logger = Logger.getInstance(TerminalSelectionUtils::class.java)

    const val ACTION_ID = "dev.sweep.assistant.actions.TerminalAddToChatAction"
    const val TERMINAL_OUTPUT_CONTEXT_MENU = "Terminal.OutputContextMenu"
    const val TERMINAL_PROMPT_CONTEXT_MENU = "Terminal.PromptContextMenu"
    const val TERMINAL_REWORKED_CONTEXT_MENU = "Terminal.ReworkedTerminalContextMenu"

    private val terminalViewDataKey: DataKey<Any> = DataKey.create("TerminalView")

    fun isTerminalPopupPlace(place: String?): Boolean =
        place == TERMINAL_OUTPUT_CONTEXT_MENU ||
            place == TERMINAL_PROMPT_CONTEXT_MENU ||
            place == TERMINAL_REWORKED_CONTEXT_MENU

    fun resolveTerminalSelectedText(
        e: AnActionEvent,
        project: Project,
    ): String? {
        resolveTerminalEditorSelection(e)?.let { return it }
        e.getData(JBTerminalWidget.SELECTED_TEXT_DATA_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        resolveReworkedTerminalSelection(e)?.let { return it }
        e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }?.let { return it }
        return TerminalManagerService
            .getInstance(project)
            .activeTerminal
            ?.selectedText
            ?.takeIf { it.isNotBlank() }
    }

    fun registerForReworkedTerminalContextMenu(): Boolean {
        val actionManager = ActionManager.getInstance()
        val action = actionManager.getAction(ACTION_ID) ?: return false
        val groupAction = actionManager.getAction(TERMINAL_REWORKED_CONTEXT_MENU) as? DefaultActionGroup ?: return false

        val alreadyRegistered =
            groupAction.getChildActionsOrStubs().any { child ->
                actionManager.getId(child) == ACTION_ID
            }
        if (alreadyRegistered) return false

        groupAction.add(action, actionManager)
        return true
    }

    private fun resolveTerminalEditorSelection(e: AnActionEvent): String? {
        val editor = getTerminalEditor(e) ?: return null
        if (!isSupportedTerminalEditor(editor)) return null
        return editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
    }

    private fun getTerminalEditor(e: AnActionEvent): Editor? {
        fun invokeGetEditor(className: String): Editor? {
            val cls = tryLoadClass(className) ?: return null

            val mByEvent =
                runCatching { cls.getMethod("getEditor", AnActionEvent::class.java) }.getOrNull()
            if (mByEvent != null) {
                val target =
                    if (java.lang.reflect.Modifier.isStatic(mByEvent.modifiers)) {
                        null
                    } else {
                        runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()
                    }
                return mByEvent.invoke(target, e) as? Editor
            }

            val mByCtx =
                runCatching {
                    cls.getMethod("getEditor", com.intellij.openapi.actionSystem.DataContext::class.java)
                }.getOrNull()
            if (mByCtx != null) {
                val target =
                    if (java.lang.reflect.Modifier.isStatic(mByCtx.modifiers)) {
                        null
                    } else {
                        runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()
                    }
                return mByCtx.invoke(target, e.dataContext) as? Editor
            }
            return null
        }

        invokeGetEditor("org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils")?.let { return it }
        return invokeGetEditor("org.jetbrains.plugins.terminal.exp.TerminalDataContextUtils")
    }

    private fun isSupportedTerminalEditor(editor: Editor): Boolean {
        val promptEditor = invokeTerminalDataContextBoolean("isPromptEditor", editor)
        val outputEditor = invokeTerminalDataContextBoolean("isOutputEditor", editor)
        val alternateBufferEditor = invokeTerminalDataContextBoolean("isAlternateBufferEditor", editor)
        return promptEditor || outputEditor || alternateBufferEditor
    }

    private fun invokeTerminalDataContextBoolean(
        methodName: String,
        editor: Editor,
    ): Boolean =
        runCatching {
            val className =
                "org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils"
            val cls = Class.forName(className)
            val instance = cls.getField("INSTANCE").get(null)
            val method = cls.methods.firstOrNull { it.name == methodName && it.parameterCount == 1 }
            method?.invoke(instance, editor) as? Boolean ?: false
        }.getOrElse {
            runCatching {
                val className = "org.jetbrains.plugins.terminal.exp.TerminalDataContextUtils"
                val cls = Class.forName(className)
                val instance = cls.getField("INSTANCE").get(null)
                val method = cls.methods.firstOrNull { it.name == methodName && it.parameterCount == 1 }
                method?.invoke(instance, editor) as? Boolean ?: false
            }.getOrDefault(false)
        }

    private fun resolveReworkedTerminalSelection(e: AnActionEvent): String? {
        val terminalView = e.getData(terminalViewDataKey) ?: return null
        return runCatching {
            val selectionModel = invokeReflective(terminalView, "getTextSelectionModel") ?: return null
            val selection = invokeReflective(selectionModel, "getSelection") ?: return null
            val startOffset = invokeReflective(selection, "getStartOffset") ?: return null
            val endOffset = invokeReflective(selection, "getEndOffset") ?: return null
            val outputModels = invokeReflective(terminalView, "getOutputModels") ?: return null
            val activeFlow = invokeReflective(outputModels, "getActive") ?: return null
            val outputModel = invokeReflective(activeFlow, "getValue") ?: return null
            invokeReflective(outputModel, "getText", startOffset, endOffset)?.toString()
        }.onFailure {
            logger.debug("[TerminalAddToChat] Reworked terminal selection resolution failed", it)
        }.getOrNull()
    }

    private fun invokeReflective(
        target: Any?,
        methodName: String,
        vararg args: Any?,
    ): Any? {
        if (target == null) return null
        val method =
            target.javaClass.methods.firstOrNull { method ->
                method.name == methodName && method.parameterCount == args.size
            } ?: target.javaClass.declaredMethods.firstOrNull { method ->
                method.name == methodName && method.parameterCount == args.size
            } ?: return null
        method.isAccessible = true
        return method.invoke(target, *args)
    }
}
