package dev.sweep.assistant.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.vim.VimMotionGhostTextService
import dev.sweep.assistant.data.ProjectFilesCache.Companion.getInstance
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.services.IdeaVimIntegrationService
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import dev.sweep.assistant.services.RipgrepManager
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepSettings

/**
 * Initializes only the services required by autocomplete.
 *
 * Chat, agent actions, authentication, MCP, telemetry, and the Sweep tool window
 * are intentionally not initialized here.
 */
class AutocompleteStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SweepProjectService.getInstance(project)
        RipgrepManager.getInstance()
        getInstance(project)
        EntitiesCache.getInstance(project)
        VimMotionGhostTextService.getInstance()
        RecentEditsTracker.getInstance(project)
        IdeaVimIntegrationService.getInstance(project).configureIdeaVimIntegration()

        if (SweepSettings.getInstance().autocompleteLocalMode) {
            ApplicationManager.getApplication().executeOnPooledThread {
                LocalAutocompleteServerManager.getInstance().ensureServerRunning()
            }
        }
    }
}
