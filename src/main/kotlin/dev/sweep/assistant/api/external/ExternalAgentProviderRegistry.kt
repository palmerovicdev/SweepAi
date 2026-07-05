package dev.sweep.assistant.api.external

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-scoped lookup for [ExternalAgentProvider] implementations. Built-in
 * providers register themselves lazily on first access; third-party providers
 * (future plugin extension point) can call [register] at startup.
 */
@Service(Service.Level.APP)
class ExternalAgentProviderRegistry {
    private val providers = ConcurrentHashMap<String, ExternalAgentProvider>()

    @Volatile
    private var builtInsRegistered = false

    fun register(provider: ExternalAgentProvider) {
        providers[provider.id] = provider
    }

    fun resolve(providerId: String): ExternalAgentProvider? {
        ensureBuiltInsRegistered()
        return providers[providerId]
    }

    fun all(): List<ExternalAgentProvider> {
        ensureBuiltInsRegistered()
        return providers.values.sortedBy { it.displayName }
    }

    private fun ensureBuiltInsRegistered() {
        if (builtInsRegistered) return
        synchronized(this) {
            if (builtInsRegistered) return
            registerBuiltIns()
            builtInsRegistered = true
        }
    }

    /**
     * OpenCode landed in Fase 2; Codex landed in Fase 3.
     */
    private fun registerBuiltIns() {
        register(dev.sweep.assistant.api.external.opencode.OpencodeAgentProvider())
        register(dev.sweep.assistant.api.external.codex.CodexAgentProvider())
    }

    companion object {
        fun getInstance(): ExternalAgentProviderRegistry =
            ApplicationManager.getApplication().getService(ExternalAgentProviderRegistry::class.java)
    }
}
