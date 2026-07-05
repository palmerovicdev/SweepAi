# Plan de Implementación: Chat Local + Commit Local con Modelo Local

## 1. Resumen

Añadir soporte completo para usar un modelo local en el chat de Sweep (con tool calls, edits, etc.) y reutilizar el mismo connector para commit messages. Configurable desde un tab "Chat" en Settings.

## 2. Arquitectura Actual

### 2.1 Chat Flow (cloud)
```
Stream.start()
  → construye ChatRequest con mensajes, snippets, reglas, herramientas, etc.
  → getConnection("backend/chat", ...) → POST a baseUrl/backend/chat
  → Backend recibe, llama al LLM (con tool definitions), orquesta el agente:
      1. LLM responde con texto + tool_calls
      2. Backend ejecuta tools (o las envía al cliente para ejecución local)
      3. Backend llama al LLM de nuevo con tool results
      4. Repite hasta que LLM responde sin tool_calls
  → Backend devuelve JSON patches en streaming (no SSE)
  → Cliente aplica patches para actualizar Message list y UI
```

El **agente loop** (llamar al LLM, ejecutar tools, llamar de nuevo) ocurre **en el backend**. El cliente solo:
- Envía el `ChatRequest`
- Recibe patches JSON y los aplica
- Ejecuta tools localmente cuando el backend se los envía (via `SweepAgentSession`)
- Envía tool results de vuelta al backend como parte del siguiente request

### 2.2 Commit Flow (cloud)
```
SweepCommitMessageService.generateCommitMessage()
  → getConnection("backend/create_commit_message")
  → POST a baseUrl/backend/create_commit_message
  → Backend llama al LLM (request simple, no streaming)
  → Devuelve JSON { "commit_message": "..." }
```

### 2.3 Tool System (reutilizable localmente)
- `ToolType.kt` — enum con todos los tools: `READ_FILE`, `STR_REPLACE`, `CREATE_FILE`, `BASH`, etc.
- `SweepTool.kt` — interfaz: `fun execute(toolCall, project, conversationId): CompletedToolCall`
- `SweepAgentSession.kt` — maneja el ciclo de vida de tool execution: ingest, schedule, await, drain
- El sistema de tools **ya está diseñado para ejecución local** — solo necesita que alguien (backend cloud) le diga qué tools ejecutar

## 3. Estrategia General

### Reemplazar el backend cloud con un orquestador local

En lugar de enviar `ChatRequest` al backend cloud, el orquestador local:

1. Construye mensajes + tool definitions en formato OpenAI Chat Completions
2. Envía POST a `{localUrl}/v1/chat/completions` con `stream: true`
3. Recibe SSE con texto + `tool_calls`
4. Ejecuta tools localmente usando `ToolType.createToolInstance()`
5. Envía tool results de vuelta al modelo
6. Loop hasta que el modelo responde sin tool_calls

Esto es el **mismo patrón de agente** que el backend cloud, pero ejecutado 100% local.

## 4. Cambios Necesarios

### 4.1 Settings — Nuevos campos en `SweepSettings.kt`

```kotlin
// ===== Local Chat =====
var chatLocalMode: Boolean = false
var chatLocalUrl: String = ""    // ej: http://localhost:11434/v1
var chatLocalModelName: String = ""  // ej: qwen2.5-coder:7b

// ===== Commit Rules =====
var commitRulesText: String = ""  // texto de reglas para commit messages
```

### 4.2 Settings UI — Tab "Chat" en `SweepSettingsConfigurable.kt`

**Layout del tab "Chat":**
```
┌──────────────────────────────────────────────┐
│ ☐ Use local chat model                       │
│                                               │
│  (solo visible si activado)                   │
│  ─────────────────────────────────           │
│  Server URL:  [http://localhost:11434/v1   ]  │
│  Model Name:  [qwen2.5-coder:7b           ]  │
│                                               │
│  Status: [Not tested]  [Test Connection]      │
│                                               │
│  ── Commit Rules ─────────────────────────    │
│  Rules for generating commit messages:        │
│  ┌────────────────────────────────────────┐   │
│  │ Use conventional commits format       │   │
│  │ Keep messages under 72 chars          │   │
│  │                                        │   │
│  └────────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
```

Nota: El tab solo aparece cuando `!SweepSettingsParser.isCloudEnvironment()` (solo en self-hosted).

### 4.3 Nueva clase: `LocalChatClient.kt`

Cliente HTTP que habla OpenAI Chat Completions API.

```kotlin
class LocalChatClient(private val project: Project) {
    private val isCancelled = AtomicBoolean(false)
    
    fun cancel()
    
    // Para chat (streaming, con tool calls)
    suspend fun streamChat(
        messages: List<OpenAIMessage>,
        model: String,
        tools: List<OpenAITool>?,
        onTextChunk: (String) -> Unit,
        onToolCalls: (List<OpenAIToolCall>) -> Unit,
    )
    
    // Para commit (no streaming, response simple)
    suspend fun completeChat(
        messages: List<OpenAIMessage>,
        model: String,
    ): String
}
```

**Formato de tool definitions** (OpenAI):
```kotlin
data class OpenAITool(
    val type: String = "function",
    val function: OpenAIFunction,
)
data class OpenAIFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,  // JSON Schema
)
```

Las tool definitions se construyen dinámicamente desde `ToolType.getAllToolNames()` y las definiciones de cada tool.

### 4.4 Orquestador Local: `LocalAgentOrchestrator.kt`

**Nuevo:** Reemplaza la lógica del backend cloud para el chat local.

```kotlin
class LocalAgentOrchestrator(
    private val project: Project,
    private val conversationId: String,
) {
    suspend fun start(
        messages: List<Message>,
        systemPrompt: String,
        snippets: List<Snippet>,
        tools: List<Map<String, String>>,  // MCP tools
        model: String,
        url: String,
        onMessageUpdated: (Message) -> Unit,
    ) {
        val client = LocalChatClient(project)
        val openAiMessages = buildMessages(messages, systemPrompt, snippets)
        val openAiTools = buildToolDefinitions(tools)
        
        var done = false
        while (!done) {
            var pendingToolCalls = mutableListOf<OpenAIToolCall>()
            
            client.streamChat(
                messages = openAiMessages,
                model = model,
                tools = openAiTools,
                onTextChunk = { delta ->
                    // Actualizar streaming del texto
                    updateAssistantMessage(delta, onMessageUpdated)
                },
                onToolCalls = { calls ->
                    pendingToolCalls.addAll(calls)
                }
            )
            
            if (pendingToolCalls.isEmpty()) {
                done = true  // Respuesta final, sin tool calls
            } else {
                // Convertir OpenAI tool_calls a Sweep ToolCall
                val sweepToolCalls = pendingToolCalls.map { it.toSweepToolCall() }
                
                // Ejecutar tools usando la infraestructura existente
                val agentSession = SweepAgentManager.getInstance(project)
                    .getOrCreateSession(conversationId)
                agentSession.ingestToolCalls(sweepToolCalls)
                agentSession.awaitToolCalls(currentMessage)
                
                // Obtener resultados y agregarlos como messages de "tool"
                val results = agentSession.completedToolCalls.map { it.toOpenAIResult() }
                openAiMessages.addAll(results)
                
                // Agregar user message fantasma para que el modelo continúe
                openAiMessages.add(OpenAIMessage(role = "user", content = "Continue with the results of the tool calls."))
            }
        }
    }
}
```

**Punto clave:** La infraestructura de tool execution (`SweepAgentSession`, `ToolType`, `SweepTool`) ya existe y funciona localmente. Solo necesitamos:
1. Traducir tool_calls de OpenAI a Sweep `ToolCall`
2. Traducir `CompletedToolCall` a OpenAI tool result
3. Orquestar el loop

### 4.5 Modificar `Stream.kt`

En `Stream.start()`, agregar branch para modo local antes de `getConnection`:

```kotlin
// Después de construir ChatRequest y finalMessages
val settings = SweepSettings.getInstance()

if (settings.chatLocalMode && settings.chatLocalUrl.isNotBlank()) {
    logger.info("[Stream.start] Using local chat model at ${settings.chatLocalUrl}")
    
    // El orquestador local maneja todo el agent loop
    val orchestrator = LocalAgentOrchestrator(project, currentConversationId)
    orchestrator.start(
        messages = finalMessages,
        systemPrompt = buildSystemPrompt(...),
        snippets = uniqueFilesToPassToSweep,
        tools = allTools,
        model = settings.chatLocalModelName,
        url = settings.chatLocalUrl,
        onMessageUpdated = { message ->
            this@Stream.onMessageUpdated(message)
        }
    )
    
    currentMarkdownDisplay.stopStreaming()
    return
}

// ... código existente (cloud) ...
```

### 4.6 Reutilizar para Commit Messages

Modificar `SweepCommitMessageService` para que use `LocalChatClient` cuando `chatLocalMode` está activo:

```kotlin
// En generateCommitMessage()
if (SweepSettings.getInstance().chatLocalMode) {
    val client = LocalChatClient(project)
    val messages = buildCommitMessages(diffString, previousCommitsString, commitTemplate)
    val rules = SweepSettings.getInstance().commitRulesText
    if (rules.isNotBlank()) {
        messages.add(0, OpenAIMessage("system", rules))
    }
    return client.completeChat(messages, modelName)
}

// ... código existente (cloud) ...
```

**Beneficio:** El mismo `LocalChatClient`, el mismo modelo local, reutilizado para dos features.

## 5. Archivos a Modificar/Crear

### Modificar:
| Archivo | Cambio |
|---|---|
| `settings/SweepSettings.kt` | +4 campos: `chatLocalMode`, `chatLocalUrl`, `chatLocalModelName`, `commitRulesText` |
| `settings/SweepSettingsConfigurable.kt` | Añadir tab "Chat" con UI completa |
| `controllers/Stream.kt` | Branch condicional en `start()` para chat local |
| `services/SweepCommitMessageService.kt` | Branch condicional en `generateCommitMessage()` para commit local |
| `components/SweepConfig.kt` | Getters delegados para nuevos campos (opcional, consistencia) |

### Nuevos:
| Archivo | Propósito |
|---|---|
| `api/LocalChatClient.kt` | Cliente HTTP OpenAI-compatible (streaming + non-streaming, tool calls, SSE) |
| `api/LocalChatModels.kt` | Data classes: `OpenAIMessage`, `OpenAITool`, `OpenAIToolCall`, `OpenAIChatCompletionRequest`, etc. |
| `api/LocalAgentOrchestrator.kt` | Orquestador del agent loop local: llama al modelo, ejecuta tools, repite |

## 6. Agent Loop Detallado

```
1. Construir mensajes iniciales:
   - system: {contenido del system prompt + tool definitions en JSON}
   - user: mensaje del usuario + snippets
   
2. Enviar a POST /v1/chat/completions (streaming)

3. SSE streaming:
   data: {"choices":[{"delta":{"role":"assistant","content":"Thinking..."}}]}
   data: {"choices":[{"delta":{"content":" Let me search"}}]}
   data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xxx","function":{"name":"search_files","arguments":"{\"query\":\"...\""}}]}]}}
   data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"}\"}}]}]}}
   data: [DONE]

4. Parsear tool_calls → List<OpenAIToolCall>
   → toolName = "search_files"
   → toolParameters = {"query": "..."}
   
5. Convertir a Sweep ToolCall:
   ToolCall(
       toolCallId = "call_xxx",
       toolName = "search_files",
       toolParameters = {"query": "..."},
       rawText = "search_files(query=...)",
       fullyFormed = true,
   )

6. Inyectar en SweepAgentSession.ingestToolCalls()
   → El sistema existente ejecuta el tool
   → Resultado en SweepAgentSession.completedToolCalls

7. Convertir CompletedToolCall a OpenAI tool result:
   {
       role: "tool",
       tool_call_id: "call_xxx",
       content: "Found 3 files: ..."
   }

8. Agregar al historial de mensajes y enviar de nuevo:
   messages = [
       ... (historial anterior)
       {role: "assistant", content: "Let me search", tool_calls: [...]},
       {role: "tool", tool_call_id: "call_xxx", content: "Found 3 files: ..."},
   ]

9. Repeat desde paso 2 hasta que no haya más tool_calls
```

## 7. Consideraciones sobre Tool Calls en Modelos Locales

| Modelo | Tool Calling |
|---|---|
| Qwen 2.5 Coder (7B+) | ✅ Excelente |
| DeepSeek Coder V2 | ✅ Bueno |
| Llama 3.1/3.2 (8B+) | ✅ Bueno |
| Mistral Nemo / Codestral | ✅ Bueno |
| DeepSeek V3 | ✅ Excelente |
| Phi-4 / Phi-3 | ⚠️ Limitado |

La mayoría de modelos modernos (7B+) soportan function calling vía OpenAI API. Para modelos que no lo soportan, se puede caer en modo "Ask" (solo texto, sin tools).

**Detección:** Si el server responde a `POST /v1/chat/completions` con tool_calls en el streaming, soporta function calling. Podemos detectarlo en el "Test Connection".

## 8. Test Connection

El botón "Test Connection" debe:
1. GET `{url}/models` (o `{url}/models/list` para Ollama)
   - Si responde → server alive
   - Extraer modelos disponibles
2. Si hay modelos, mostrar dropdown de selección en vez de text field
3. Si no hay endpoint `/models`, mostrar el text field manual

```kotlin
suspend fun testConnection(url: String): ConnectionTestResult {
    return try {
        val resp = httpClient.get("$url/models")
        if (resp.status == 200) {
            val models = resp.body()["data"]?.map { it["id"] }
            ConnectionTestResult.Success(models)
        } else {
            // Fallback: probar /models/list (Ollama)
            val resp2 = httpClient.get("$url/models/list")
            if (resp2.status == 200) {
                val models = resp2.body()["models"]?.map { it["name"] }
                ConnectionTestResult.Success(models)
            } else {
                ConnectionTestResult.Success(null) // Server OK pero no pudimos listar modelos
            }
        }
    } catch (e: Exception) {
        ConnectionTestResult.Failure(e.message)
    }
}
```

## 9. Plan de Implementación

### Fase 1: Data Models + Settings (~2h)
1. Crear `api/LocalChatModels.kt` → `OpenAIMessage`, `OpenAITool`, `OpenAIToolCall`, `OpenAIChatCompletionChunk`, etc.
2. Añadir campos a `SweepSettings.kt`
3. Modificar `SweepSettingsConfigurable.kt` — añadir tab "Chat" con UI completa
4. Delegar desde `SweepConfig.kt` (opcional)

### Fase 2: LocalChatClient (~3h)
1. Implementar `streamChat()` con SSE parsing
2. Implementar `completeChat()` sin streaming
3. Manejar tool_calls en streaming (acumulación por `index`)
4. Manejo de errores, timeouts, cancelación
5. Test connection

### Fase 3: LocalAgentOrchestrator (~4h)
1. Construir mensajes iniciales (system + user + snippets + tool definitions)
2. Loop del agente: stream → parse tool_calls → execute → repeat
3. Traducción OpenAI ↔ Sweep tool formats
4. Integración con `SweepAgentSession` para tool execution
5. Integración con `Stream.start()` para actualización de UI

### Fase 4: Commit Messages (~1h)
1. Modificar `SweepCommitMessageService.generateCommitMessage()`
2. Usar `LocalChatClient.completeChat()` cuando chatLocalMode está activo
3. Incluir `commitRulesText` en el system prompt

### Fase 5: Polish (~1h)
1. Notificaciones de error
2. Badge/indicador de modo local en el chat
3. "Test Connection" con auto-detección de modelos
4. Documentación

## 10. Resumen Técnico

```
┌─────────────────────────────────────────────────────┐
│                   Stream.start()                     │
│                                                       │
│  ┌─────────────────────────────────────────────┐     │
│  │ ¿chatLocalMode?                              │     │
│  │   NO  → getConnection("backend/chat") [cloud]│     │
│  │   SÍ  → LocalAgentOrchestrator.start()       │     │
│  │          ↓                                    │     │
│  │          LocalChatClient.streamChat()         │     │
│  │          ↓                                    │     │
│  │          OpenAI SSE → parse text + tool_calls │     │
│  │          ↓                                    │     │
│  │          ¿tool_calls?                         │     │
│  │            NO  → DONE (respuesta final)       │     │
│  │            SÍ  → SweepAgentSession            │     │
│  │                  → ToolType.createInstance()  │     │
│  │                  → SweepTool.execute()        │     │
│  │                  ↓                            │     │
│  │                  Enviar results de vuelta     │     │
│  │                  → loop al streamChat()       │     │
│  └─────────────────────────────────────────────┘     │
│                                                       │
│                   Commit Messages                     │
│  ┌─────────────────────────────────────────────┐     │
│  │ ¿chatLocalMode?                              │     │
│  │   NO  → getConnection("backend/...") [cloud] │     │
│  │   SÍ  → LocalChatClient.completeChat()       │     │
│  └─────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────┘
```

## 11. Diagrama del Agent Loop

```
Messages: [system, user, ...assistant...]
                │
                ▼
    POST /v1/chat/completions {stream: true, tools: [...]}
                │
                ▼
          SSE Streaming
          ┌─────────────┐
          │ text chunk   │──→ updateMessage(content)
          │ tool_call Δ  │──→ accumulateToolCall(index, delta)
          │ [DONE]       │
          └─────────────┘
                │
                ▼
          ¿accumulated tool_calls?
          ├── NO  → DONE ✓
          └── SÍ  → translate & execute
                      │
                      ▼
            SweepAgentSession.ingestToolCalls()
            SweepAgentSession.awaitToolCalls()
                      │
                      ▼
            Translate results → OpenAI format
                      │
                      ▼
            Add to Messages: [..., assistant(tool_calls), tool(result)]
                      │
                      └──→ loop (back to POST)
```

## 12. Próximas Mejoras (post-MVP)

- [ ] Caching de tool definitions (no reconstruirlas cada request)
- [ ] Soporte para streaming de thinking blocks
- [ ] Parallel tool calling (enviar múltiples tool_calls simultáneamente)
- [ ] Soporte para modelos sin function calling (modo "Ask" fallback)
- [ ] Selector de modelo dinámico desde el server local
- [ ] Auto-descarga de modelos (similar a autocomplete local managed)
- [ ] Campo de API key opcional para servers que requieren auth
