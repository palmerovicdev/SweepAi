package dev.sweep.assistant.controllers

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.actions.TerminalActionUtil
import com.jediterm.terminal.ui.TerminalAction
import com.jediterm.terminal.ui.TerminalActionProvider
import dev.sweep.assistant.utils.TerminalSelectionUtils

internal class LegacyTerminalSendActionProvider(
    widget: JBTerminalWidget,
    private var nextProvider: TerminalActionProvider?,
) : TerminalActionProvider {
    private val actions: List<TerminalAction> =
        createAction(widget)?.let { listOf(it) } ?: emptyList()

    override fun getActions(): List<TerminalAction> = actions

    override fun getNextProvider(): TerminalActionProvider? = nextProvider

    override fun setNextProvider(provider: TerminalActionProvider?) {
        nextProvider = provider
    }

    companion object {
        private val logger = Logger.getInstance(LegacyTerminalSendActionProvider::class.java)

        fun installIfClassicTerminal(widget: JBTerminalWidget) {
            if (widget.javaClass.name != "org.jetbrains.plugins.terminal.ShellTerminalWidget") return
            runCatching {
                val currentNext = widget.terminalPanel.nextProvider
                widget.terminalPanel.nextProvider =
                    LegacyTerminalSendActionProvider(widget, currentNext)
                logger.info("[TerminalAddToChat] Installed legacy terminal action provider")
            }.onFailure {
                logger.warn("[TerminalAddToChat] Failed to install legacy terminal action provider", it)
            }
        }

        private fun createAction(widget: JBTerminalWidget): TerminalAction? {
            val action = ActionManager.getInstance().getAction(TerminalSelectionUtils.ACTION_ID)
            if (action == null) {
                logger.warn("[TerminalAddToChat] Action not registered: ${TerminalSelectionUtils.ACTION_ID}")
                return null
            }
            return TerminalActionUtil.createTerminalAction(widget, action)
        }
    }
}
