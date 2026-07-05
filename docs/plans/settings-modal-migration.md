# Plan pendiente — migrar Sweep Settings modal a Preferences

Estado de trabajo restante del refactor "big plan: modal → Preferences + commit-msg via el modelo del chat" (plan file: `~/.claude/plans/vamos-a-hacer-el-declarative-glade.md`).

## Contexto

En la sesión inicial se completó:
- **Parte B (commit-msg via chat model)** — `NodeBridgeClient.oneShotCompletion(...)` + branch de `SweepCommitMessageService` por `chatProviderId` (con codex/opencode genera vía sidecar con el mismo modelo del chat).
- **A4** — botón gear "Settings" del title bar del chat eliminado, campo `settingsAction` retirado de `SweepActionManager`; los action ids `SweepSettings` y `SweepOpenSettings` siguen registrados apuntando a `openSettingsAction`.
- **A1/A2/A3 parcial** — 2 de 8 tabs migradas a `Configurable` (BYOK, Custom Prompts, las que ya tenían factory pública) + 3 de 11 callers de `showConfigPopup` redirigidos a `ShowSettingsUtil` (`AddCustomPromptAction`, `ReviewPRAction`, `Sweep.openSettingsAction`).

El modal `SweepConfig.showConfigPopup(...)` sigue vivo y funcional. Los tabs no migrados se siguen abriendo desde ahí.

## Lo que queda

### 1. Extraer 6 tabs de `SweepConfig.showConfigPopup` a factories públicas

`showConfigPopup` va de la línea ~3024 a la ~6280 en `src/main/kotlin/dev/sweep/assistant/components/SweepConfig.kt`. Cada tab está definido inline como `val <panel>` dentro del `apply { }` del `JTabbedPane` (algunos como Rules están antes del tabbedPane).

Los bloques cierran sobre ~15-20 variables locales de `showConfigPopup` que hay que promover a campos `internal` nullable en `SweepConfig`:

- `rulesDescriptionLabel`, `rulesContentLabel`, `rulesPathLabel` (compartidos entre Rules + descriptionPanel + otras secciones).
- `rulesHeaderPanel`, `autocompleteHeaderPanel`, `descriptionPanel`, `rulesContentPanel`.
- `createSweepMdPanel`, `createUserRulesPanel`.
- `commitTemplateContentPanel`, `globalCommitRulesPanel` (Rules tab).
- `maxTabsHeaderPanel`, `maxTabsPanel`, `nextEditCompletionFlagCheckbox`, `debouncePanel` (Features tab).
- `exclusionPatternsField`, `bashCommandAllowlistChipPanel`, `bashCommandBlocklistChipPanel` (Advanced tab — hoy sólo se flushean en `doOKAction`).

Extractions concretas por tab (números de línea al momento de escribir):

| Tab | Rango | Factory objetivo | Riesgo |
|---|---|---|---|
| Features (`generalSettingsPanel`) | 4135-4331 | `internal fun createFeaturesPanel(): JPanel` | Alto — depende de max-tabs, autocomplete-header, debounce panel, `privacyModeCheckBox` (ya es field). |
| Advanced (`advancedSettingsPanel`) | 4362-4705 | `internal fun createAdvancedPanel(): JPanel` | Medio — depende de `exclusionPatternsField` + chip panels. Migrar la lógica de `doOKAction` (flush chip patterns → `SweepSettings`) al `apply()` del Configurable. |
| Account (`apiSettingsPanel`) | 4706-5007 | `internal fun createAccountPanel(): JPanel` | Medio — usa `configDialog?.close(0)` tras acciones de auth (aceptar como no-op en Preferences). |
| Agent (`toolCallsSettingsPanel`) | 5008-5593 | `internal fun createAgentPanel(): JPanel` | Medio — 585 líneas, la mayor. Revisar closures cuidadosamente. |
| MCP Servers (`mcpServersSettingsPanel`) | 5594-6089 | `internal fun createMcpServersPanel(): JPanel` | Medio — 495 líneas + `configDialog?.close(0)` en callbacks. |
| Rules (`rulesPanel`) | 3980-4099 | `internal fun createRulesPanel(): JPanel` | Bajo — sólo depende de las labels compartidas + los pre-panels. Extraer primero los `descriptionPanel`, `rulesContentPanel`, `createSweepMdPanel`, `createUserRulesPanel`, `commitTemplateContentPanel`, `globalCommitRulesPanel` a builders privados. |

**Estrategia recomendada**: hacer los tabs en orden de menor a mayor riesgo (Rules → Advanced → Features → Account → MCP Servers → Agent). Entre cada tab: compilar + commit.

### 2. Crear 6 nuevos Configurables

Uno por tab, patrón cortito (ya tenemos plantilla en `SweepByokConfigurable.kt` / `SweepCustomPromptsConfigurable.kt`). En `src/main/kotlin/dev/sweep/assistant/settings/`:

```kotlin
class SweepXxxConfigurable(private val project: Project) : Configurable {
    private var component: JPanel? = null
    override fun createComponent(): JComponent =
        (component ?: SweepConfig.getInstance(project).createXxxPanel()).also { component = it }
    override fun isModified(): Boolean = false     // panel auto-commits
    override fun apply() { /* excepto Advanced/Features → flush chip patterns */ }
    override fun reset() { /* si el panel guarda estado interno, aplicar en createComponent */ }
    override fun getDisplayName(): String = "Xxx"
    override fun disposeUIResources() { component = null }
}
```

Excepciones que sí necesitan `apply()` real:
- **`SweepAdvancedConfigurable.apply()`** debe copiar la lógica actual de `SweepConfig.doOKAction` (líneas 6220-6225): flush de `exclusionPatternsField.text` → `updateAutocompleteExclusionPatterns(...)` y los chip panels → `updateBashCommandAllowlist/Blocklist(...)`.
- **`SweepFeaturesConfigurable.apply()`** — revisar si algún widget del Features tab depende del OK batch (probablemente no; los checkboxes tienen listeners inline).

### 3. Registrar los 6 Configurables en `plugin.xml`

Añadir en `src/main/resources/META-INF/plugin.xml` (junto a los ya existentes `SweepChatProviderConfigurable`, `SweepByokConfigurable`, `SweepCustomPromptsConfigurable`):

```xml
<projectConfigurable
    parentId="dev.sweep.assistant.settings.SweepSettingsConfigurable"
    displayName="Features"
    id="dev.sweep.assistant.settings.SweepFeaturesConfigurable"
    instance="dev.sweep.assistant.settings.SweepFeaturesConfigurable"/>
<!-- idem para Account, Agent, McpServers, Rules, Advanced -->
```

Fijar orden con `order="first"` / `order="after ..."` para reflejar el orden del modal antiguo.

### 4. Redirigir los 7 callers restantes de `showConfigPopup`

| Caller | Nueva llamada |
|---|---|
| `components/WelcomeScreen.kt:58` | `ShowSettingsUtil.showSettingsDialog(project, SweepSettingsConfigurable::class.java)` |
| `controllers/Stream.kt:801` | `SweepSettingsConfigurable::class.java` (o el más relevante al error) |
| `notifications/AutocompleteExclusionNotificationProvider.kt:66` | `SweepAdvancedConfigurable::class.java` (requiere #2 Advanced hecho) |
| `services/SweepAuthServer.kt:91` | `SweepAccountConfigurable::class.java` (requiere #2 Account) |
| `services/SweepMcpService.kt:609` | `SweepMcpServersConfigurable::class.java` (requiere #2 MCP Servers) |
| `settings/GitHubAuthHandler.kt:233` | `SweepAccountConfigurable::class.java` |
| `statusbar/FrontendStatusBarWidget.kt:27` | `SweepSettingsConfigurable::class.java` |

### 5. Borrar el modal

Una vez todos los tabs migrados y los 10 callers redirigidos:

- Borrar `fun showConfigPopup(tabName: String? = null)` de `SweepConfig.kt` (líneas ~3024-6280).
- Borrar el `DialogWrapper` anónimo y todo el árbol Swing inline que no se haya movido a las factories.
- Borrar `createLeftSideActions` con el link "Questions/Feedback?" (si se quiere conservar, moverlo como banner en `SweepSettingsConfigurable.createComponent()`).
- Borrar los campos `tabbedPane` y `configDialog` de `SweepConfig` (líneas 264-265) — ya no necesarios.
- Borrar el incremento de `SweepMetaData.configButtonClicks` (ya no hay gear, `SweepMetaData.kt:15`).

El archivo `SweepConfig.kt` debería pasar de ~6628 a ~3500 líneas.

## Verificación

- `./gradlew compileKotlin` compila limpio en cada sub-fase.
- Cmd+, → `Tools → Sweep` → confirmar los 8 sub-nodos (Chat Provider, Features, Account, Agent, MCP Servers, Rules, BYOK, Custom Prompts, Advanced).
- Cada Configurable: cambiar un valor, `Apply`, cerrar y reabrir → persiste.
- Los 10 callers redirigidos abren la pestaña correcta en Preferences.
- El title bar del chat NO tiene gear (History, Report, NewChat sí).
- `grep -rn "showConfigPopup" src/main/kotlin` → 0 hits al terminar Fase 5.
- Search Everywhere → "Sweep Settings" sigue funcionando (via `SweepOpenSettings` action).

## Notas / riesgos

- **`configDialog?.close(0)` en callbacks de "abrir archivo"**: hoy el modal se cierra tras abrir un template. En Preferences no hay dialog que cerrar — dejar la llamada como no-op (`configDialog` será null cuando se llame desde un Configurable) o registrar `PreferencesUtil` para cerrar Preferences. Aceptar el no-op como comportamiento válido.
- **Batch OK/Cancel semantics**: hoy el modal aplica todos los cambios en `doOKAction`. Preferences aplica por-Configurable en Apply. Verificar que `SweepConfig.updateXxx(...)` sean idempotentes y no dependan del flush conjunto.
- **`SweepConstants.GATEWAY_MODE`**: el título del modal cambia a "Sweep Settings (Client/Host)". Preferences no tiene título variable — aceptar la regresión.
- **Duplicación con `SweepSettingsConfigurable` existente**: cubre autocomplete básicos. La Features tab del modal es un superset. Al crear `SweepFeaturesConfigurable`, evaluar si consolidar o mantener `SweepSettingsConfigurable` como parent "Sweep" (raíz) y meter todo lo específico en las sub-Configurables.

## Referencias

- Plan original: `~/.claude/plans/vamos-a-hacer-el-declarative-glade.md`
- Configurables ya migrados: `SweepByokConfigurable.kt`, `SweepCustomPromptsConfigurable.kt`
- Factories ya expuestas: `SweepConfig.createBYOKPanel()`, `SweepConfig.createCustomPromptsPanel()` (marcadas `internal`)
