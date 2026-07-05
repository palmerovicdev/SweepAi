package dev.sweep.assistant.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService

data class CustomPrompt(
    var name: String = "",
    var prompt: String = "",
    var includeSelectedCode: Boolean = true,
)

data class BYOKProviderConfig(
    var apiKey: String = "",
    var eligibleModels: List<String> = emptyList(),
)

@State(
    name = "dev.sweep.jetbrains.settings.SweepSettings",
    storages = [Storage("SweepSettings.xml")],
)
class SweepSettings : PersistentStateComponent<SweepSettings> {
    companion object {
        private const val DEFAULT_GITHUB_TOKEN = ""
        private const val DEFAULT_SWEEP_URL = ""
        private const val DEFAULT_BETA_FLAG_ON = false
        private const val DEFAULT_NEXT_EDIT_PREDICTION_ON = true
        private const val DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW = true
        private const val DEFAULT_ANTHROPIC_API_KEY = ""
        private const val DEFAULT_PLAY_NOTIFICATION_ON_STREAM_END = false
        private const val DEFAULT_DEVELOPER_MODE_ON = false

        // -1L means "unset" so project-level values can migrate in
        private const val DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS = -1L

        // Default to false - do not automatically disable conflicting autocomplete plugins
        private const val DEFAULT_DISABLE_CONFLICTING_PLUGINS = true

        fun getInstance(): SweepSettings = ApplicationManager.getApplication().getService(SweepSettings::class.java)

        fun isAppleSilicon(): Boolean {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            return os.contains("mac") && (arch == "aarch64" || arch == "arm64")
        }
    }

    // Do not notify settings changed on each save, fire it in config instead
    fun interface SettingsChangedNotifier {
        fun settingsChanged()

        companion object {
            @JvmField
            val TOPIC = Topic.create("Sweep settings changed", SettingsChangedNotifier::class.java)
        }
    }

    var githubToken: String = DEFAULT_GITHUB_TOKEN
        get() = field.trim()
        set(value) {
            if (value != field) {
                field = value // make sure you report recent data
                notifySettingsChanged()
                TelemetryService.getInstance().sendUsageEvent(EventType.USER_AUTHENTICATED)
            } else {
                field = value
            }
        }

    var baseUrl: String = DEFAULT_SWEEP_URL
        get() =
            if (SweepSettingsParser.isCloudEnvironment()) {
                SweepEnvironmentConstants.Defaults.DEFAULT_BASE_URL
            } else {
                field.trim().trimEnd('/')
            }
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var betaFlagOn: Boolean = DEFAULT_BETA_FLAG_ON
        set(value) {
            field = value
        }

    var nextEditPredictionFlagOn: Boolean = DEFAULT_NEXT_EDIT_PREDICTION_ON
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
                // Send telemetry when autocomplete is disabled
                if (!value) {
                    TelemetryService.getInstance().sendUsageEvent(EventType.AUTOCOMPLETE_DISABLED)
                }
            } else {
                field = value
            }
        }

    var acceptWordOnRightArrow: Boolean = DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var anthropicApiKey: String = DEFAULT_ANTHROPIC_API_KEY
        get() = field.trim()
        set(value) {
            field = value
        }

    var playNotificationOnStreamEnd: Boolean = DEFAULT_PLAY_NOTIFICATION_ON_STREAM_END
        set(value) {
            field = value
        }

    var developerModeOn: Boolean = DEFAULT_DEVELOPER_MODE_ON
        set(value) {
            field = value
        }

    /**
     * Autocomplete debounce delay in milliseconds.
     * This is stored at the application level and applies to all projects.
     * A value of -1 indicates "unset" and allows a one-time migration from any existing
     * project-level setting in SweepConfig when first accessed.
     */
    var autocompleteDebounceMs: Long = DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS
        set(value) {
            val clamped = value.coerceIn(10L, 1000L)
            field = clamped
            // We intentionally do not fire notifySettingsChanged here to avoid
            // excessive message bus chatter while the user drags the slider.
        }

    /**
     * Automatically disable conflicting autocomplete plugins.
     * This is stored at the application level and applies to all projects.
     */
    var disableConflictingPlugins: Boolean = DEFAULT_DISABLE_CONFLICTING_PLUGINS
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var customPrompts: MutableList<CustomPrompt> = mutableListOf()
        set(value) {
            field = value
            notifySettingsChanged()
        }

    var hasInitializedDefaultPrompts: Boolean = false

    /**
     * BYOK (Bring Your Own Key) provider configurations.
     * This is stored at the application level and applies to all projects.
     * Map of provider name -> BYOKProviderConfig (apiKey, eligibleModels)
     */
    var byokProviderConfigs: MutableMap<String, BYOKProviderConfig> = mutableMapOf()
        set(value) {
            field = value
            // Don't notify settings changed for BYOK to avoid excessive chatter
        }

    var autocompleteLocalMode: Boolean = false

    // Whether to automatically start the local server on IDE startup.
    // Default false for new installs. A one-time migration in loadState() flips
    // this on for users who already had autocompleteLocalMode = true before
    // the flag existed, so they don't silently lose auto-start on upgrade.
    var autoStartLocalServer: Boolean = false

    // Tracks whether the one-time autoStartLocalServer migration has run.
    // Do not set this manually — see loadState().
    var autoStartLocalServerMigrated: Boolean = false

    var autocompleteLocalPort: Int = 8081

    var autocompleteLocalModelRepo: String = "sweepai/sweep-next-edit-0.5B"

    var autocompleteLocalModelFilename: String = "sweep-next-edit-0.5b.q8_0.gguf"

    var autocompleteExternalUrl: String = ""

    // "llamacpp" (default, GGUF via sweep-autocomplete) or "mlx" (Apple Silicon, sweep-autocomplete-mlx)
    var autocompleteBackend: String = "llamacpp"

    // Community-maintained MLX conversion of sweep-next-edit-v2. Not under an
    // org Sweep controls — a compromised upload could produce adversarial
    // completions (mlx-lm does not enable trust_remote_code, so this is not a
    // direct RCE vector). Users may override in Settings; fork maintainers
    // should point this at a repo they control or pin a specific revision.
    // To pin a revision, set autocompleteMlxModelRevision to a specific commit hash.
    var autocompleteMlxModelRepo: String = "Cyanophyte/sweep-next-edit-v2-7B-mlx-8Bit"

    // Pin the MLX model repo to a specific commit hash for supply-chain hardening.
    // Empty string means "latest" (default).
    var autocompleteMlxModelRevision: String = ""

    // ===== External Agent Chat =====
    // "sweep-cloud" (default), "local", "opencode", "codex"
    var chatProviderId: String = "sweep-cloud"
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    // OpenCode
    var opencodeCommand: String = "opencode"
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    var opencodeExtraArgs: String = ""
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // Empty = Sweep manages the process; non-empty = user-provided base URL.
    var opencodeBaseUrl: String = ""
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    var opencodeAgent: String = "build"
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    // Codex
    var codexCommand: String = "codex"
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    var codexExtraArgs: String = ""
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // Empty = use Codex's persisted default.
    var codexModel: String = ""
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // OpenCode model in `providerID/modelID` shape; empty = let the OpenCode
    // server pick its default. Kept separate from `codexModel` because the
    // two providers accept different model-id conventions.
    var opencodeModel: String = ""
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // "never" | "on-request" | "on-failure" | "untrusted"
    var codexApprovalPolicy: String = "on-request"
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // "read-only" | "workspace-write" | "danger-full-access"
    var codexSandbox: String = "workspace-write"
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // "" | "minimal" | "low" | "medium" | "high"
    // Empty = leave codex to pick (from config.toml or default).
    var codexReasoningEffort: String = ""
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // "" | "shown" | "hidden"
    // Controls Codex `hide_agent_reasoning`. Empty = respect Codex's own default.
    var codexThinking: String = ""
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    // Absolute path to node executable; empty = auto-detect via NodeDetector.
    var aiBridgeNodePath: String = ""
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // npm spec used when installing @openai/codex-sdk inside the ai-bridge dir.
    // Pinned to a specific reviewed version so a compromised `latest` release
    // does not silently ship RCE inside the daemon. Users can widen this to
    // "latest" or any semver spec via Settings → Chat Provider.
    var codexSdkVersion: String = "0.142.5"
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }
    // npm spec used when installing @opencode-ai/sdk inside the ai-bridge dir.
    // See [codexSdkVersion] for the pinning rationale.
    var opencodeSdkVersion: String = "1.17.13"
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    // Toggled to true by SdkManager after a successful `npm install`. Used by
    // [hasBeenSet] so selecting `chatProviderId=codex/opencode` alone does not
    // short-circuit the onboarding flow until the bridge is actually usable.
    var bridgeSdksInstalled: Boolean = false

    fun ensureDefaultPromptsInitialized() {
        var addedPrompt = false

        if (customPrompts.none { it.name == "AI Code Review" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "AI Code Review",
                    prompt = "Review each of the changes in detail for potential bugs",
                    includeSelectedCode = false,
                ),
            )
            addedPrompt = true
        }

        if (customPrompts.none { it.name == "Explain Code" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "Explain Code",
                    prompt = "Explain what the code does.",
                    includeSelectedCode = true,
                ),
            )
            addedPrompt = true
        }

        if (customPrompts.none { it.name == "Write Documentation" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "Write Documentation",
                    prompt = "Please write documentation for the highlighted code.",
                    includeSelectedCode = true,
                ),
            )
            addedPrompt = true
        }

        if (addedPrompt) {
            // Trigger state save by creating a new list instance to change the reference
            customPrompts = customPrompts.toMutableList()
        }

        if (!hasInitializedDefaultPrompts || addedPrompt) {
            hasInitializedDefaultPrompts = true
        }
    }

    val useLocalMode: Boolean
        get() = SweepSettingsParser.isCloudEnvironment() && anthropicApiKey.isNotEmpty()

    /**
     * Determines if the user has configured Sweep settings if either:
     * 1. Both GitHub token and base URL have been set to non-default values, OR
     * 2. An Anthropic API key has been provided, OR
     * 3. An external chat provider (OpenCode / Codex) is selected AND its
     *    SDKs have actually been installed — merely picking the provider is
     *    not enough, because the bridge would still fail on first send.
     */
    val hasBeenSet: Boolean
        get() {
            if (chatProviderId in setOf("opencode", "codex") && bridgeSdksInstalled) return true
            return if (SweepSettingsParser.isCloudEnvironment()) {
                githubToken != DEFAULT_GITHUB_TOKEN
            } else {
                githubToken != DEFAULT_GITHUB_TOKEN && baseUrl != DEFAULT_SWEEP_URL
            }
        }

    fun notifySettingsChanged() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager
                .getApplication()
                ?.messageBus
                ?.syncPublisher(SettingsChangedNotifier.TOPIC)
                ?.settingsChanged()
        }
    }

    fun runNowAndOnSettingsChange(
        project: Project,
        parentDisposable: Disposable,
        callback: SweepSettings.() -> Unit,
    ) {
        this.callback()
        project.messageBus.connect(parentDisposable).subscribe(
            SettingsChangedNotifier.TOPIC,
            SettingsChangedNotifier {
                getInstance().callback()
            },
        )
    }

    fun initiateGitHubAuth(project: Project) {
        GitHubAuthHandler.initiateAuth(project)
    }

    override fun getState(): SweepSettings = this

    override fun loadState(state: SweepSettings) {
        XmlSerializerUtil.copyBean(state, this)
        // Initialize default prompts after loading state
        ensureDefaultPromptsInitialized()
        // One-time migration for users upgrading from a version where
        // autocompleteLocalMode alone drove startup — preserve their behavior.
        if (!autoStartLocalServerMigrated) {
            if (autocompleteLocalMode) autoStartLocalServer = true
            autoStartLocalServerMigrated = true
        }
    }
}
