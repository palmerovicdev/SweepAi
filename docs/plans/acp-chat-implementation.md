# Plan de Implementación: Chat via ACP (Agent Client Protocol)

## 1. Resumen

Conectar el chat de Sweep con agentes externos mediante el **Agent Client Protocol (ACP)**, un protocolo abierto estandarizado (JSON-RPC 2.0 sobre stdio) que define cómo los IDEs (Clients) se comunican con agentes de IA (Agents).

ACP permite usar cualquier agente compatible como backend de chat: **Codex**, **OpenCode**, **Cursor**, y **Claude Code**, sin necesidad de integraciones personalizadas para cada uno.

## 2. ¿Qué es ACP?

- **Protocolo abierto** respaldado por JetBrains, Zed, y la comunidad
- Comunicación **bidireccional** sobre stdio (newline-delimited JSON-RPC 2.0)
- Define dos roles:
  - **Agent**: programa que usa IA para modificar código autónomamente (Codex, Cursor, Claude Code, etc.)
  - **Client**: editor/IDE que interactúa con el usuario y controla recursos
- Soporta **sesiones** (`session/new`, `session/load`, `session/prompt`)
- Permite **notificaciones en tiempo real** (`session/update` con chunks de texto, tool_calls, etc.)
- Integración con **MCP** (Model Context Protocol) para exponer tools del cliente
- SDKs oficiales: TypeScript, Rust, Kotlin, Java, Python

Documentación: https://agentclientprotocol.com

## 3. Soporte de ACP por cada Herramienta

| Herramienta | ¿ACP Nativo? | Cómo se conecta | SDK/Librería |
|---|---|---|---|
| **Cursor** | ✅ Sí (`agent acp`) | stdio subprocess con JSON-RPC | `cursor agent acp` |
| **Claude Code** | ❌ No nativo | Adapter `claude-code-acp` (vía Claude Agent SDK) | `npm i -g @zed-industries/claude-code-acp` |
| **Codex (OpenAI)** | ❌ No nativo | Usa su propio app-server protocol JSON-RPC, o MCP | `codex app-server --listen stdio://` |
| **OpenCode** | ❌ No nativo | HTTP Server con OpenAPI 3.1 + SDK JS/TS | `opencode serve`, `@opencode-ai/sdk` |

### Detalle por Herramienta

#### 3.1 Cursor (`agent acp`)
- Comando: `cursor agent acp` (o `agent acp`)
- Auth: `cursor_login` (pre-autenticar con `agent login` o `CURSOR_API_KEY`)
- MCP: soporta `.cursor/mcp.json` local
- Modos: `ask` (read-only), `plan` (planning), `agent` (full tools)
- Flujo: `initialize` → `authenticate` → `session/new` → `session/prompt`

#### 3.2 Claude Code (vía `claude-code-acp`)
- Comando: `claude-code-acp` (Node.js, npm global)
- Auth: `ANTHROPIC_API_KEY`
- Usa Claude Agent SDK oficial
- Soporta: context @-mentions, images, tool calls, terminals, edit review, TODO lists
- Flujo: mismo ACP estándar

#### 3.3 Codex (vía app-server protocol)
- Comando: `codex app-server --listen stdio://`
- Protocolo propio JSON-RPC (NO ACP, pero similar)
- Primitivas: Thread, Turn, Item
- Soporta streaming diffs, approval workflows, MCP tools
- Requiere adapter para traducir de ACP a app-server protocol

#### 3.4 OpenCode (vía HTTP API)
- Comando: `opencode serve --port 4096`
- API REST con OpenAPI 3.1
- SDK: `@opencode-ai/sdk`
- NO es ACP, pero expone sesiones, prompts, y eventos vía HTTP/SSE
- Requiere adapter HTTP → ACP

## 4. Arquitectura Propuesta

### 4.1 Visión General

```
┌─────────────────────────────────────────────┐
│              Sweep Chat UI                   │
│  (MessageList, MarkdownDisplay, etc.)        │
├─────────────────────────────────────────────┤
│           ChatComponent / Stream             │
├─────────────────────────────────────────────┤
│            ACP Client Engine                 │
│  (nuevo: maneja conexión stdio JSON-RPC)     │
├─────────────────────────────────────────────┤
│                                             │
│  ┌────────┐ ┌──────────┐ ┌──────┐ ┌──────┐ │
│  │ Cursor │ │ClaudeCode│ │Codex │ │Open..│ │
│  │ agent  │ │claude-   │ │codex │ │open..│ │
│  │ acp    │ │code-acp  │ │app-  │ │serve │ │
│  │        │ │          │ │server│ │      │ │
│  └────────┘ └──────────┘ └──────┘ └──────┘ │
└─────────────────────────────────────────────┘
```

### 4.2 Flujo de Chat vía ACP

```
1. Usuario escribe mensaje en Sweep Chat UI
2. Stream.start() detecta modo ACP (vs cloud local)
3. ACP Client Engine:
   a. Spawnea el proceso ACP del agente seleccionado (stdio subprocess)
   b. Envía initialize → authenticate → session/new
   c. Envía session/prompt con el mensaje del usuario
4. El agente responde vía:
   - session/update notifications (texto streaming)
   - session/request_permission (tool calls)
5. ACP Client Engine:
   - Traduce session/update → actualizaciones de MessageList
   - Traduce tool calls → SweepAgentSession para ejecución local
   - Envía permisos/respuestas de vuelta al agente
6. Cuando el agente termina → muestra respuesta final
```

### 4.3 Comparación: Flujo Actual vs ACP

| Aspecto | Cloud Actual | ACP |
|---|---|---|
| Transporte | HTTP POST a backend | stdio JSON-RPC 2.0 |
| Streaming | JSON patches vía HTTP | session/update notifications |
| Tool Calls | Backend orquesta, cliente ejecuta | Agente solicita, cliente autoriza/ejecuta |
| Sesiones | conversationId en backend | session/new en agente |
| Auth | API key del backend | Cada agente tiene su propio auth |

## 5. Cambios Necesarios

### 5.1 Settings — Nuevos campos en `SweepSettings.kt`

```kotlin
// ===== ACP Chat =====
var acpEnabled: Boolean = false
var acpAgentType: String = ""  // "cursor", "claude-code", "codex", "opencode"
var acpCommand: String = ""    // path al ejecutable (auto-detectable)
var acpArgs: String = ""       // argumentos adicionales
var acpEnvVars: String = ""    // JSON de env vars (API keys, etc.)
```

### 5.2 Settings UI — Tab "ACP" en `SweepSettingsConfigurable.kt`

```
┌──────────────────────────────────────────────┐
│  ☐ Enable ACP Agent Chat                     │
│                                               │
│  Agent Type:  [▼ Cursor             ]         │
│               [  Claude Code        ]         │
│               [  Codex              ]         │
│               [  OpenCode           ]         │
│                                               │
│  Executable:  [agent              ] [Detect]  │
│  Arguments:   [acp                ]          │
│                                               │
│  Environment Variables:                       │
│  ┌────────────────────────────────────────┐   │
│  │ {                                      │   │
│  │   "ANTHROPIC_API_KEY": "sk-..."       │   │
│  │ }                                      │   │
│  └────────────────────────────────────────┘   │
│                                               │
│  Status: [Not connected]  [Test Connection]   │
└──────────────────────────────────────────────┘
```

### 5.3 Nuevos Archivos

| Archivo | Propósito |
|---|---|
| `api/acp/AcpClient.kt` | Cliente ACP base: conexión stdio, JSON-RPC, lifecycle |
| `api/acp/AcpModels.kt` | Data classes: `AcpRequest`, `AcpResponse`, `AcpNotification`, `SessionUpdate`, etc. |
| `api/acp/AcpSession.kt` | Manejo de sesión ACP: create, prompt, cancel, close |
| `api/acp/AcpAgentManager.kt` | Factory/manager de agentes: cursor, claude-code, codex, opencode |
| `api/acp/agents/CursorAgent.kt` | Configuración específica para Cursor |
| `api/acp/agents/ClaudeCodeAgent.kt` | Configuración específica para Claude Code |
| `api/acp/agents/CodexAgent.kt` | Adapter de app-server protocol a ACP |
| `api/acp/agents/OpencodeAgent.kt` | Adapter de HTTP API a ACP |
| `api/acp/StreamToAcpBridge.kt` | Traduce Stream.start() a llamadas ACP |

### 5.4 Modificar Archivos Existentes

| Archivo | Cambio |
|---|---|
| `settings/SweepSettings.kt` | +4 campos ACP |
| `settings/SweepSettingsConfigurable.kt` | Añadir tab "ACP" |
| `controllers/Stream.kt` | Branch condicional en `start()` para modo ACP |
| `components/SweepConfig.kt` | UI de selección de agente ACP |

### 5.5 `AcpClient.kt` — Core del Protocolo

```kotlin
class AcpClient(
    private val project: Project,
    private val agentType: AcpAgentType,
) {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var requestId = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect(command: String, args: List<String>, env: Map<String, String>)

    // Initialize handshake
    suspend fun initialize(): InitializeResult

    // Authenticate (if needed)
    suspend fun authenticate(method: String, credentials: Map<String, String>)

    // Session management
    suspend fun createSession(params: SessionNewParams): String  // returns sessionId
    suspend fun promptSession(sessionId: String, message: String)

    // Listen for notifications
    fun onNotification(handler: (AcpNotification) -> Unit)

    // Cancel current operation
    suspend fun cancelSession(sessionId: String)

    // Cleanup
    fun disconnect()
}
```

### 5.6 `AcpSession.kt` — Sesión de Chat

```kotlin
class AcpSession(
    private val client: AcpClient,
    private val sessionId: String,
) {
    // Enviar prompt y recibir streaming
    suspend fun prompt(
        message: String,
        onTextChunk: (String) -> Unit,
        onToolCall: (AcpToolCall) -> Unit,
        onComplete: () -> Unit,
    )

    // Responder a solicitud de permiso de tool
    suspend fun respondToPermission(permissionId: String, allow: Boolean)

    // Cancelar
    suspend fun cancel()
}
```

### 5.7 `StreamToAcpBridge.kt` — Integración con Stream.kt

```kotlin
class StreamToAcpBridge(
    private val project: Project,
    private val conversationId: String,
) {
    suspend fun start(
        messages: List<Message>,
        systemPrompt: String,
        onMessageUpdated: Stream.(Message) -> Unit,
    ) {
        val settings = SweepSettings.getInstance()
        val agentType = AcpAgentType.fromString(settings.acpAgentType)
        val agent = AcpAgentManager.createAgent(agentType)

        val client = AcpClient(project, agentType)
        client.connect(agent.command, agent.args, agent.envVars)

        // Initialize handshake
        client.initialize()
        if (agent.requiresAuth) {
            client.authenticate(agent.authMethod, agent.authCredentials)
        }

        // Create session
        val sessionId = client.createSession(SessionNewParams(
            cwd = project.basePath,
            mcpServers = loadMcpServers(),
        ))

        // Stream prompt
        val session = AcpSession(client, sessionId)
        session.prompt(
            message = buildPrompt(messages, systemPrompt),
            onTextChunk = { chunk ->
                // Update MessageList with streaming text
            },
            onToolCall = { toolCall ->
                // Execute tool via SweepAgentSession
                executeToolAndReturnResult(toolCall)
            },
            onComplete = {
                // Finalize message
            }
        )
    }
}
```

### 5.8 Modificar `Stream.kt`

En `Stream.start()`, agregar branch para modo ACP:

```kotlin
val settings = SweepSettings.getInstance()

if (settings.acpEnabled && settings.acpAgentType.isNotBlank()) {
    logger.info("[Stream.start] Using ACP agent: ${settings.acpAgentType}")

    val bridge = StreamToAcpBridge(project, currentConversationId)
    bridge.start(
        messages = finalMessages,
        systemPrompt = buildSystemPrompt(...),
        onMessageUpdated = { message ->
            this@Stream.onMessageUpdated(message)
        },
    )

    currentMarkdownDisplay.stopStreaming()
    return
}
```

## 6. Traducción Tool Calls (ACP → Sweep)

ACP envía tool calls como `session/request_permission`. Sweep necesita traducirlas a su sistema de tools.

```kotlin
// ACP tool call
data class AcpToolCall(
    val permissionId: String,
    val toolName: String,      // "read_file", "bash", "str_replace", etc.
    val args: Map<String, Any>,
)

// Traducción a Sweep ToolCall
fun AcpToolCall.toSweepToolCall(): ToolCall = ToolCall(
    toolCallId = permissionId,
    toolName = this.toolName,
    toolParameters = this.args,
    rawText = "${toolName}(${args.entries.joinToString { "${it.key}=${it.value}" }})",
    fullyFormed = true,
)

// Resultado de vuelta a ACP
fun CompletedToolCall.toAcpResult(): Map<String, Any> = mapOf(
    "success" to !isError,
    "output" to (result ?: error ?: ""),
    "exitCode" to 0,
)
```

## 7. Configuración por Agente

### 7.1 Cursor

```kotlin
object CursorAgent : AcpAgentConfig {
    override val type = AcpAgentType.CURSOR
    override val defaultCommand = "agent"
    override val defaultArgs = listOf("acp")
    override val requiresAuth = true
    override val authMethod = "cursor_login"

    override fun detectCommand(): String? {
        // Buscar en PATH, ~/.local/bin/agent, etc.
        return findInPath("agent")
    }

    override fun getEnvSuggestions(): Map<String, String> = mapOf(
        "CURSOR_API_KEY" to "",
    )
}
```

### 7.2 Claude Code

```kotlin
object ClaudeCodeAgent : AcpAgentConfig {
    override val type = AcpAgentType.CLAUDE_CODE
    override val defaultCommand = "claude-code-acp"
    override val defaultArgs = emptyList()
    override val requiresAuth = true
    override val authMethod = "api_key"

    override fun detectCommand(): String? {
        // Buscar en PATH o sugerir npm install -g
        return findInPath("claude-code-acp") ?: run {
            notify("Install with: npm install -g @zed-industries/claude-code-acp")
            null
        }
    }

    override fun getEnvSuggestions(): Map<String, String> = mapOf(
        "ANTHROPIC_API_KEY" to "",
    )
}
```

### 7.3 Codex

Codex NO es ACP nativo. Usa su propio app-server protocol (JSON-RPC). Necesita un adapter.

```kotlin
object CodexAgent : AcpAgentConfig {
    override val type = AcpAgentType.CODEX
    override val defaultCommand = "codex"
    override val defaultArgs = listOf("app-server", "--listen", "stdio://")
    override val requiresAuth = false  // Codex maneja auth internamente
    override val authMethod = "none"

    // NOTA: Codex usa su propio protocolo, NO ACP.
    // Necesita un bridge que traduzca:
    //   ACP → thread/start, turn/start, item/* events
    // Esto requiere implementar AcpCodexBridge
}
```

### 7.4 OpenCode

OpenCode NO es ACP nativo. Expone API REST HTTP. Necesita adapter HTTP → ACP.

```kotlin
object OpencodeAgent : AcpAgentConfig {
    override val type = AcpAgentType.OPENCODE
    override val defaultCommand = "opencode"
    override val defaultArgs = listOf("serve", "--port", "0")
    override val requiresAuth = true
    override val authMethod = "basic_auth"

    // NOTA: OpenCode usa HTTP API, no stdio JSON-RPC.
    // Necesita conectar vía HTTP a localhost (puerto dinámico)
    // y traducir: GET/POST HTTP → ACP messages
    // Esto requiere implementar AcpOpencodeBridge
}
```

## 8. Consideraciones Técnicas

### 8.1 Manejo de Procesos
- Spawnear agente como subproceso al iniciar sesión
- Mantener proceso vivo entre prompts para reutilizar sesión
- Terminar proceso al cerrar chat o proyecto
- Timeout: 30s para connect, 5min para prompt

### 8.2 Detección de Agentes
- Buscar en PATH automáticamente
- Botón "Detect" que escanea rutas comunes
- Fallback: entrada manual de comando

### 8.3 Manejo de Errores
- Connection refused → mostrar notificación amigable
- Auth failed → sugerir login
- Timeout → opción de cancelar/reintentar
- Proceso crash → reinicio automático con límite

### 8.4 MCP Integration
- Los agentes ACP soportan MCP servers del cliente
- Sweep ya tiene sistema MCP (SweepMcpService)
- En `initialize`, pasar MCP servers configurados como `clientCapabilities`
- Los tools MCP se vuelven disponibles para el agente

### 8.5 Límites y Restricciones
- No todos los agentes soportan parallel tool calling
- Algunos agentes requieren login previo (Cursor)
- Codex y OpenCode necesitan adapters (no son ACP nativos)
- Claude Code necesita API key de Anthropic

## 9. Plan de Implementación

### Fase 1: Core ACP (~2 semanas)
1. Crear `api/acp/AcpModels.kt` — data classes base
2. Crear `api/acp/AcpClient.kt` — conexión stdio, JSON-RPC lifecycle
3. Crear `api/acp/AcpSession.kt` — manejo de sesiones
4. Crear `api/acp/AcpAgentManager.kt` — registry de agentes
5. Tests unitarios con agente mock

### Fase 2: Agente Cursor (~1 semana)
1. Crear `api/acp/agents/CursorAgent.kt`
2. Integrar con Stream.kt vía StreamToAcpBridge
3. Traducción de tool calls ACP ↔ Sweep
4. Probar: chat básico, tool calls, permisos
5. Test Connection button

### Fase 3: Settings UI (~3 días)
1. Añadir campos a `SweepSettings.kt`
2. Crear tab "ACP" en configurable
3. Auto-detección de agente
4. Estado de conexión

### Fase 4: Claude Code (~1 semana)
1. Crear `api/acp/agents/ClaudeCodeAgent.kt`
2. Probar con `claude-code-acp`
3. Manejo de auth (ANTHROPIC_API_KEY)

### Fase 5: Codex y OpenCode (~2 semanas)
1. Crear adapters: `CodexAgent.kt`, `OpencodeAgent.kt`
2. Codex: traducir app-server protocol ↔ ACP
3. OpenCode: traducir HTTP API ↔ ACP
4. Probar ambos

### Fase 6: Polish (~1 semana)
1. Badge de agente activo en chat
2. Selector rápido de agente (dropdown en chat)
3. Logging de comunicación ACP
4. Notificaciones de error/reconexión
5. Documentación

## 10. Diagrama de Flujo ACP

```
Sweep Chat UI                    ACP Client Engine                 Agent Process
     │                                  │                              │
     │  Usuario escribe mensaje          │                              │
     │─────────────────────────────────>│                              │
     │                                  │  spawn()                      │
     │                                  │──────────────────────────────>│
     │                                  │  initialize()                 │
     │                                  │──────────────────────────────>│
     │                                  │  <───────── result ──────────│
     │                                  │  authenticate()               │
     │                                  │──────────────────────────────>│
     │                                  │  <───────── result ──────────│
     │                                  │  session/new                  │
     │                                  │──────────────────────────────>│
     │                                  │  <──── sessionId ────────────│
     │                                  │  session/prompt               │
     │                                  │──────────────────────────────>│
     │                                  │                              │
     │  <── session/update (text) ──────│                              │
     │  (streaming)                     │                              │
     │                                  │                              │
     │  <── session/request_permission ─│                              │
     │  (tool call)                     │                              │
     │                                  │                              │
     │  ── allow/reject ───────────────>│                              │
     │                                  │  tool result                 │
     │                                  │──────────────────────────────>│
     │                                  │                              │
     │  <── session/update (text) ──────│                              │
     │                                  │                              │
     │  <── session/prompt response ────│                              │
     │  (stop reason: end_turn)         │                              │
     │                                  │                              │
```

## 11. Riesgos y Mitigaciones

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Agente no disponible en PATH | No funciona | Auto-detección + instrucciones de instalación |
| Auth falla | No conecta | Guía paso a paso en UI |
| Proceso se cuelga | Chat congelado | Timeout + kill + reconectar |
| Tool calls no mapean bien | Comportamiento inesperado | Mapeo explícito + fallback a "ask" mode |
| Codex/OpenCode no son ACP | Más trabajo | Adapters separados, priorizar Cursor y Claude Code |
| Consumo de recursos (procesos hijos) | Performance | Pool de procesos, timeout de idle |

## 12. Próximas Mejoras (post-MVP)

- [ ] Selector rápido de agente en la barra de herramientas del chat
- [ ] Hot-swap de agente durante una conversación
- [ ] Soporte para múltiples agentes simultáneos (comparación)
- [ ] Registro ACP propio (Sweep como agente ACP)
- [ ] Integración con ACP Registry (agentclientprotocol.com)
- [ ] Persistencia de sesiones ACP (reanudar después de reinicio)
- [ ] Proxy de MCP tools del IDE al agente
- [ ] Cost tracking por sesión/agente
