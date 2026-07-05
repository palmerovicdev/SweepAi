# Plan de Implementación: Chat con Agentes Externos (OpenCode y Codex)

> Versión: 1 · Fecha: 2026-07-05 · Estado: **Fases 1–6 completadas + Follow-ups v1.29.5** (tool window registrado, Test Connection con timeout defensivo, model/effort/thinking como dropdowns en Settings y en quick-picker del chat).
> Reemplaza para efectos de implementación al MVP definido en `acp-chat-implementation.md` (que se mantiene como referencia futura para agentes ACP nativos, ver §3.4).
> Complementa a `local-chat-implementation.md` (chat local con un LLM directo vía OpenAI-compatible API).

## Estado actual (última actualización: 2026-07-05, v1.29.5)

| Fase | Estado |
|---|---|
| Fase 0 — Preparación | ✅ hecho (OkHttp/Ktor + kotlinx-serialization ya disponibles) |
| Fase 1 — Data models + Settings + skeleton | ✅ hecho (7 campos + `codexReasoningEffort` + `codexThinking`, pestaña "Chat Provider" con dropdowns, registry, session store, engine stub) |
| Fase 2 — OpenCode provider | ✅ hecho (process + HTTP/SSE + adapter + Test Connection con timeout ping) |
| Fase 3 — Codex provider | ✅ hecho (process + JSON-RPC client + adapter + provider + Test Connection + `-c` config overrides para effort/thinking) |
| Fase 4 — Engine + Stream integration | ✅ hecho (engine `stream`/`cancel`/`onProviderChanged` + Stream routing + `recordExternalCompletion` + guard en `ingestToolCalls`) |
| Fase 5 — UX polish | ✅ hecho (badge external en tooltip, permission modal, toasts accionables, `newRemoteSession` API, **tool window Sweep AI registrado en `plugin.xml`**, **quick-picker en la barra superior del chat**) |
| Fase 6 — Tests, docs, release | ✅ hecho (`ExternalAgentProtocolTest` + README + brainstorm + change-notes v1.29.5; integration tests con fakes quedan como follow-up) |

## Follow-ups completados en v1.29.5

- **Bug fix — Test Connection colgado:** `SweepChatProviderConfigurable.runTestConnection` ahora envuelve el probe en `withTimeout(45 s)`; adicionalmente `OpencodeHttpClient.ping()` corta a 5 s con `withTimeout` propio en vez de heredar el `requestTimeout = 0` del cliente Ktor (que sí necesitamos para SSE).
- **Tool window en la barra:** `<toolWindow id="Sweep AI" anchor="right" icon="/icons/sweep13x13.svg" factoryClass="dev.sweep.assistant.Sweep"/>` registrado en `plugin.xml`. La `SweepStartupActivity` sigue sin engancharse para no arrastrar dependencias cloud del plan original; el `Sweep.createToolWindowContent` popula los `titleActions` por sí solo.
- **Dropdowns en Settings:** `codexModel` pasa de `JBTextField` a un `JComboBox` editable con presets (`gpt-5-codex`, `gpt-5.5`, etc.). Se añaden dropdowns para `Reasoning effort` (`minimal|low|medium|high`) y `Thinking` (`shown|hidden`) que se propagan al subproceso como `-c model_reasoning_effort=...` y `-c hide_agent_reasoning=...` antes del subcomando `app-server`.
- **Quick-picker en el chat:** `ExternalAgentQuickSettings` (`src/main/kotlin/dev/sweep/assistant/views/`) — pequeño widget visible sólo cuando `chatProviderId ∈ {opencode, codex}` que abre un popup con los mismos dropdowns de Settings, sincronizado por `SettingsChangedNotifier`. Se ancla en el `leftContainer` del `topRow` del `ChatComponent`.

---

## 1. Objetivo general

Añadir al chat de Sweep la capacidad de delegar la conversación a **agentes externos** (herramientas de línea de comandos que son ya de por sí agentes con tools, sandbox, permisos y memoria propios), integrándolos como *providers* seleccionables desde la UI.

En esta primera iteración se soportarán dos proveedores:

| Proveedor | Comando | Transporte | Rol en Sweep |
|---|---|---|---|
| **OpenCode** | `opencode serve` | HTTP REST + SSE (localhost) | Provider "OpenCode" |
| **Codex** (OpenAI) | `codex app-server --listen stdio://` | stdio + JSON-RPC 2.0 | Provider "Codex" |

El chat de Sweep deja de ser únicamente un cliente del backend cloud propio y pasa a poder actuar como **shell/front-end** que enruta al agente externo elegido. El sistema queda preparado para añadir nuevos proveedores (Claude Code, Cursor, etc.) sin re-arquitectura.

## 2. Alcance inicial (MVP)

**Dentro del alcance:**

- Selector de "Chat Provider" en Settings: `sweep-cloud` (actual, default) · `local` (ya planeado) · `opencode` · `codex`.
- Ciclo completo *usuario → mensaje → respuesta con streaming* contra OpenCode y Codex.
- Streaming de texto y de eventos `tool_call` / `tool_result` mostrados en el `MarkdownDisplay` existente, con la misma UX que el chat cloud actual.
- Autodetección del ejecutable (`opencode`, `codex`) en PATH y campo manual como fallback.
- Reanudación de conversaciones ya existentes en el store del proveedor (Codex `thread/resume`, OpenCode `GET /session/:id`) desde el historial de Sweep.
- Cancelación con el mismo botón "Stop" de Sweep.
- Botón "Test Connection" con verificación viva del proceso/endpoint.
- Persistencia mínima: mapa `conversationId → { providerId, remoteSessionId }`.
- Modo **"delegado"** por defecto: los tools los ejecuta el agente externo (usan su propio sandbox y aprobación). Sweep solo *observa* y *renderiza* los eventos.

**Fuera de alcance (roadmap para siguiente iteración):**

- Modo **"host tools"**: que el agente externo pida a Sweep ejecutar sus 25 herramientas (`SweepTool`) en el IDE. Solo se dejará el hook y el data-flow preparado.
- Bridge con `SweepMcpService` para exponer los MCP servers configurados en Sweep al agente externo (habrá `TODO` documentado, no código).
- Auth interactiva del proveedor (`codex login`, `opencode auth`) desde el propio panel — el usuario debe iniciarla externamente en su terminal.
- Chat multi-agente simultáneo en un mismo conversationId.
- Reemplazo del flujo actual de commit messages (queda como está — cloud o local según `local-chat-implementation.md`).
- Soporte ACP nativo (`agent acp`, `claude-code-acp`).

## 3. Diferencias entre el flujo ACP y el flujo local — y por qué este plan

El plan `acp-chat-implementation.md` propone un cliente ACP genérico como *columna vertebral* y adapters específicos por proveedor (incluyendo Codex/OpenCode). Ese enfoque tiene dos costes que este plan evita para el MVP:

1. **Coste de traducción**: ni Codex ni OpenCode son ACP nativos. Traducir ACP ↔ *codex app-server protocol* y ACP ↔ *OpenCode REST/SSE* duplica el trabajo: primero se define ACP, y luego se implementa igualmente el adapter proveedor-específico.
2. **Coste de mantenimiento**: ambos protocolos son estables por proveedor pero *evolucionan independientes* de ACP (Codex ya está en `rust_v0_29_1_alpha_7`; OpenCode ha estabilizado un `sessions.prompt` v2). Un layer ACP intermedio se convierte en superficie extra que se rompe.

### 3.1 Comparación de los tres enfoques

| Aspecto | `local-chat` (LLM directo) | ACP (`acp-chat`) | **Este plan (proveedores externos directos)** |
|---|---|---|---|
| Naturaleza del backend | LLM raw (Ollama/LM Studio) | Agente ACP-compatible | Agente CLI con protocolo propio |
| Transporte | HTTP `POST /v1/chat/completions` (SSE) | stdio JSON-RPC 2.0 | HTTP+SSE (OpenCode) o stdio JSON-RPC 2.0 (Codex) |
| Agent loop | En Sweep (`LocalAgentOrchestrator`) | En el agente ACP | **En el agente externo** |
| Ejecución de tools | Sweep local (`SweepAgentSession`) | Cliente (Sweep) autoriza y ejecuta | **Agente externo** (delegado). Sweep observa. |
| Auth | Ninguna o API key | Por agente (login) | Por proveedor (login previo en terminal) |
| Sesión | Sin sesión persistente | `session/new` en agente | `thread/*` (Codex) · `POST /session` (OpenCode) |
| Cancelación | Cancelar SSE | `session/cancel` | `codex interruptTurn` · `POST /session/:id/abort` |

### 3.2 Qué se hereda de cada plan

- De `local-chat-implementation.md`: la disciplina de un tab "Chat" en Settings con `Test Connection`, la abstracción `ChatProvider`, y el punto de entrada único en `Stream.start()`.
- De `acp-chat-implementation.md`: la idea de manejar el subproceso stdio (para Codex), el mapeo de tool calls, y la nomenclatura de *engine* / *bridge*.

## 4. Arquitectura propuesta

### 4.1 Visión general

```
┌─────────────────────────────────────────────────────────────┐
│                     Sweep Chat UI                            │
│   (MessageList · MarkdownDisplay · ChatComponent)            │
├─────────────────────────────────────────────────────────────┤
│                Stream.start(...)                             │
│                                                              │
│   switch (SweepSettings.chatProviderId)                     │
│     ├─ "sweep-cloud"  →  getConnection("backend/chat")  ─┐  │
│     ├─ "local"        →  LocalAgentOrchestrator         │  │ (planes existentes)
│     └─ "opencode"|"codex" ↓                              │  │
│           ExternalAgentChatEngine.stream(...)            │  │
├─────────────────────────────────────────────────────────────┤
│              ExternalAgentChatEngine                         │
│                                                              │
│   uses ExternalAgentProvider (interface, per-provider):      │
│   ┌────────────────────────┐ ┌────────────────────────┐     │
│   │ OpencodeAgentProvider  │ │  CodexAgentProvider     │     │
│   │  (HTTP+SSE)            │ │   (stdio JSON-RPC 2.0)  │     │
│   └────────┬───────────────┘ └────────┬───────────────┘     │
│            │                          │                     │
│  ┌─────────▼──────────┐   ┌───────────▼────────────────┐    │
│  │ OpencodeProcess    │   │ CodexProcess               │    │
│  │  (opencode serve)  │   │  (codex app-server ...)    │    │
│  └────────────────────┘   └────────────────────────────┘    │
├─────────────────────────────────────────────────────────────┤
│  ExternalAgentEvent (normalizado) → traducido a Sweep Message│
│  · TextDelta  · ToolCallStarted  · ToolCallCompleted         │
│  · PermissionRequested  · TurnCompleted  · Error             │
├─────────────────────────────────────────────────────────────┤
│                onMessageUpdated(message) → UI                │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Principios de diseño

1. **Un solo punto de entrada en `Stream.start()`**. Igual que el plan local, un `switch` sobre el provider seleccionado antes de la ruta cloud actual. No se toca la lógica de mensajes/snippets/mentions.
2. **`ExternalAgentProvider` es la abstracción**. Aísla lo específico del proveedor. Codex y OpenCode implementan la misma interfaz.
3. **Un tipo de evento normalizado** (`ExternalAgentEvent`). Todo lo que emiten los proveedores se traduce a este tipo, que se convierte a `Message` incrementalmente. Añadir Claude Code / Cursor mañana = una nueva implementación, cero cambios río abajo.
4. **Proceso por conversación, no global**. Cada `conversationId` tiene su `ExternalAgentProcess` con vida ligada a la conversación. Se apaga al cerrar la conversación o cambiar de proveedor.
5. **Delegación de tools por defecto** para el MVP. El agente externo ejecuta sus tools; Sweep muestra la actividad. En §7 se documenta el hook para el modo *host-tools* futuro.
6. **Compatibilidad con `SweepAgentSession`**. El `SweepAgentSession` existente sigue montándose (para que la UI cuente pending/completed y respete el "Stop"), pero recibe *tool calls sintéticos* que se marcan como *completados por externo* de forma inmediata. Detalle en §7.3.

## 5. Componentes principales

### 5.1 Nuevos (todos en `dev.sweep.assistant.api.external`)

| Archivo | Responsabilidad |
|---|---|
| `ExternalAgentProvider.kt` | Interfaz común. Ver §11.1. |
| `ExternalAgentChatEngine.kt` | Orquesta un stream: elige provider, arranca proceso, crea/carga sesión remota, envía prompt, consume eventos, actualiza `Message`. |
| `ExternalAgentEvent.kt` | Sealed class de eventos normalizados. Ver §11.2. |
| `ExternalAgentProviderRegistry.kt` | Registro `providerId → ExternalAgentProvider`. Lookup y detección. |
| `ExternalAgentSessionStore.kt` | Persistencia `conversationId ↔ (providerId, remoteSessionId)` (SQLite reutilizando la infraestructura de `ChatHistory`). |
| `opencode/OpencodeAgentProvider.kt` | Implementación OpenCode. |
| `opencode/OpencodeProcess.kt` | Ciclo de vida de `opencode serve --port <n>` (o `--print-logs` según versión) + health check. |
| `opencode/OpencodeHttpClient.kt` | Wrapper OkHttp/Ktor sobre la API REST + SSE listener sobre `GET /event`. |
| `opencode/OpencodeModels.kt` | Data classes de request/response (Session, Message, Part, EventListResponse). |
| `codex/CodexAgentProvider.kt` | Implementación Codex. |
| `codex/CodexProcess.kt` | Ciclo de vida de `codex app-server --listen stdio://`. |
| `codex/CodexJsonRpcClient.kt` | Cliente JSON-RPC 2.0 line-delimited sobre stdin/stdout del proceso (framing, id, futures). |
| `codex/CodexModels.kt` | Data classes: `Thread`, `Turn`, `Item`, `TurnStarted`, `TurnCompleted`, `ItemToolCall`, `ItemToolCallOutput`. |
| `codex/CodexProtocolAdapter.kt` | Traduce eventos Codex → `ExternalAgentEvent`. |
| `opencode/OpencodeProtocolAdapter.kt` | Traduce eventos SSE OpenCode → `ExternalAgentEvent`. |

### 5.2 Modificados

| Archivo | Cambio |
|---|---|
| `settings/SweepSettings.kt` | +7 campos (§6). |
| `settings/SweepSettingsConfigurable.kt` | Nueva pestaña **"Chat Provider"** (§13.2). |
| `components/SweepConfig.kt` | Getters/setters delegados para los nuevos campos (patrón actual). |
| `controllers/Stream.kt` | Rama en `start()` (§10.1). |
| `services/ChatHistory.kt` | Migración de esquema para tabla `external_agent_session` (§14). |
| `services/SweepSessionManager.kt` / `SessionMessageList` | Extender para conservar `remoteSessionId` en metadatos de sesión (opcional pero útil para "reconnect"). |
| `startup/AutocompleteStartupActivity.kt` (o equivalente) | Registrar el `ExternalAgentProviderRegistry` como servicio de aplicación al arranque. |

## 6. Nuevos campos en `SweepSettings`

Siguiendo el patrón actual de campos planos + notificaciones:

```kotlin
// ===== External Agent Chat =====
// "sweep-cloud" (default), "local", "opencode", "codex"
var chatProviderId: String = "sweep-cloud"

// OpenCode
var opencodeCommand: String = "opencode"      // ejecutable en PATH o path absoluto
var opencodeExtraArgs: String = ""            // args opcionales antes de "serve"
var opencodeBaseUrl: String = ""              // opcional: si el usuario ya levantó opencode serve manualmente
                                              // (si vacío, Sweep gestiona el proceso)
var opencodeAgent: String = "build"           // "build" | "plan" | "chat" (perfil de OpenCode)

// Codex
var codexCommand: String = "codex"
var codexExtraArgs: String = ""               // se concatenan tras "app-server --listen stdio://"
var codexModel: String = ""                   // vacío = usa el default persistido por Codex
var codexApprovalPolicy: String = "on-request" // "never" | "on-request" | "on-failure" | "untrusted"
var codexSandbox: String = "workspace-write"   // "read-only" | "workspace-write" | "danger-full-access"
```

Notas:

- No se guardan tokens/API keys aquí: Codex y OpenCode gestionan su propia auth por usuario (Codex OAuth/API key vía `codex login`; OpenCode con `opencode auth`). Ver §16.
- Todos los campos disparan `notifySettingsChanged()` para que `Stream` recoja cambios entre mensajes.

## 7. Flujo de mensajes

### 7.1 Flujo principal (feliz)

```
Usuario envía mensaje
   │
   ▼
Stream.start(...)      (sin cambios en su preparación de mensajes/snippets)
   │
   ▼
if (chatProviderId in {"opencode","codex"})
   │
   ▼
ExternalAgentChatEngine.stream(
   conversationId,
   finalMessages,
   currentFilePath,
   systemPromptExtras,   // reglas del proyecto, snippets, etc.
   onMessageUpdated,
)
   │
   ▼
1) providers.resolve(settings.chatProviderId)              → provider
2) sessionStore.getOrCreate(conversationId, provider)      → (isNew, remoteSessionId, process)
3) provider.ensureProcessRunning(process, settings, project)
4) provider.ensureRemoteSession(remoteSessionId, cwd=project.basePath) → sessionId
5) provider.sendUserMessage(sessionId, promptText, images) → returns eventStream
6) por cada ExternalAgentEvent:
      TextDelta   → append content al último assistant Message · onMessageUpdated
      ToolCallStarted → SweepAgentSession.ingestToolCalls(list of synthetic ToolCall)
                        con annotations.thinkingOnly (marca "ejecutado externamente")
      ToolCallCompleted → registrar CompletedToolCall (status según el evento)
      PermissionRequested → mostrar diálogo IntelliJ. respuesta → provider.answerPermission(...)
      TurnCompleted → cerrar el assistant Message · guardar historial
      Error       → propagar como Message con annotations.notification
```

### 7.2 Cancelación

- `Stream.stop()` invoca `ExternalAgentChatEngine.cancel(conversationId)`.
- El engine llama a `provider.cancelCurrentTurn(sessionId)`:
  - **Codex**: método experimental `codex/interruptTurn` (fallback: cerrar y reiniciar el subproceso).
  - **OpenCode**: `POST /project/:pid/session/:sid/abort`.
- Se marca el `Message` en curso como interrumpido y se emite `stopStreaming = "user-cancelled"` para que la UI lo respete (mismo path que hoy).

### 7.3 Puente con `SweepAgentSession`

`SweepAgentSession` no ejecuta los tools en modo delegado; pero **sí se sigue usando** como *libro contable* de tool calls para que la UI existente los renderice sin cambios:

```
ExternalAgentEvent.ToolCallStarted(toolName, args, id)
   → agentSession.ingestToolCalls(listOf(ToolCall(
         toolCallId = id, toolName = toolName,
         toolParameters = args.mapValues { it.toString() },
         rawText = renderRaw(toolName, args),
         fullyFormed = true,
         mcpProperties = mapOf("executor" to providerId),  // marca "externo"
      )))

ExternalAgentEvent.ToolCallCompleted(id, ok, result)
   → agentSession.recordExternalCompletion(CompletedToolCall(
         toolCallId = id, ..., status = ok,
         resultString = result, mcpProperties = mapOf("executor" to providerId),
      ))
```

Se añade a `SweepAgentSession` un método `recordExternalCompletion(...)` que inserta directo en `completedToolCalls` **sin schedular ejecución** (nueva ruta paralela a `scheduleIfReady`). Esto reutiliza toda la maquinaria de UI + persistencia sin duplicar código.

### 7.4 Diagrama secuencial (Codex)

```
UI          Stream        Engine        CodexProvider     CodexProcess    codex app-server
 │  msg      │              │                │                │                │
 │──────────▶│              │                │                │                │
 │           │──stream(msg)─▶│                │                │                │
 │           │              │──ensureProc()─▶│                │                │
 │           │              │                │──spawn/attach─▶│                │
 │           │              │                │                │──subprocess──▶ │
 │           │              │◀───ready───────│                │                │
 │           │              │──initialize()─▶│                │                │
 │           │              │                │──JSON-RPC──────────initialize──▶│
 │           │              │                │◀───result──────────────────────│
 │           │              │──ensureSession─▶│                │                │
 │           │              │                │──thread/start (or thread/resume)▶
 │           │              │                │◀───thread{id}─────────────────│
 │           │              │──sendUserMsg──▶│                │                │
 │           │              │                │──turn/start (input=[{text}])──▶│
 │           │              │                │◀───turn{id, inProgress}────────│
 │           │              │◀───TextDelta──────(item/agent_message.delta)────│
 │  update ◀─│              │                │                │                │
 │           │              │◀───ToolCallStarted (item/tool/call.started)─────│
 │  render ◀─│              │                │                │                │
 │           │              │◀───ToolCallCompleted (item/tool/call.completed)─│
 │           │              │◀───TurnCompleted (turn/completed)───────────────│
 │           │◀──done───────│                │                │                │
 │  final  ◀─│              │                │                │                │
```

### 7.5 Diagrama secuencial (OpenCode)

```
UI          Stream        Engine        OpencodeProvider   OpencodeProcess    opencode serve
 │  msg      │              │                │                │                │
 │──────────▶│              │                │                │                │
 │           │──stream(msg)─▶│                │                │                │
 │           │              │──ensureProc()─▶│                │                │
 │           │              │                │──spawn or reuse▶                │
 │           │              │                │                │──subprocess──▶ │
 │           │              │◀───ready(port)─│                │                │
 │           │              │──ensureSession─▶│                │                │
 │           │              │                │──POST /project/:pid/session────▶│
 │           │              │                │◀── {sessionId} ────────────────│
 │           │              │──subscribe(SSE)─▶│                │                │
 │           │              │                │──GET /event (Accept: text/event-stream)
 │           │              │                │                │──(open)──────▶ │
 │           │              │──sendUserMsg──▶│                │                │
 │           │              │                │──POST /session/:sid/message ──▶│
 │           │              │                │                │                │
 │           │              │◀───TextDelta──── (message.updated + Part text)──│
 │  update ◀─│              │                │                │                │
 │           │              │◀───ToolCallStarted (message.updated + ToolPart)─│
 │           │              │◀───ToolCallCompleted (message.updated + ToolPart)│
 │           │              │◀───TurnCompleted (message.updated + final)──────│
```

## 8. Manejo de sesiones

**Modelo:** una conversación de Sweep (`conversationId`) mapea a **una** sesión remota del proveedor. No se re-crea por mensaje.

### 8.1 Estados

```
NONE          → no hay sesión remota aún
CREATING      → se está pidiendo al proveedor una sesión nueva
READY(remoteId) → se puede prompt-ear
STREAMING(remoteId) → hay un turn en curso
CANCELLING    → se pidió cancelar
DEAD(reason)  → el proceso o la sesión murió; el engine la recrea perezosamente
```

### 8.2 Reglas

1. Al primer mensaje del usuario en una conversación: si no hay `remoteSessionId`, se llama a `provider.createSession(cwd=project.basePath, agent=settings.opencodeAgent)` (OpenCode) o `thread/start` (Codex) y se guarda en `ExternalAgentSessionStore`.
2. Al reabrir Sweep con una conversación previa: el engine intenta `thread/resume` (Codex) o `GET /session/:id` (OpenCode). Si falla (404, proceso fresco), se cae a "crear nueva sesión" y se marca la conversación en la UI con un aviso "*Sesión previa no recuperable, iniciando nueva*".
3. Al cambiar `chatProviderId` en Settings mientras la conversación tiene mensajes: no se migra; se crea una sesión remota nueva en el nuevo proveedor y se avisa al usuario ("El proveedor cambió; los mensajes anteriores permanecen en el historial pero el nuevo agente no tiene ese contexto"). Se puede pegar el historial como texto en el system prompt como mitigación (§13.3).
4. `Stream.stop()` NO destruye la sesión remota, solo cancela el turn. Recuperar el sessionId sigue siendo válido.
5. `DELETE conversation` en el sidebar de Sweep: elimina también la sesión remota (best-effort — si falla, se registra pero no bloquea).

### 8.3 Proceso vs sesión

- Codex es *un proceso por conversación* (más simple; el proceso administra un único `thread` activo cómodamente, y matarlo libera memoria si el usuario pausa).
- OpenCode es *un proceso compartido para todo el IDE* (`opencode serve` en un puerto local aleatorio). Cada conversación se mapea a una sesión distinta dentro de ese único proceso.

Este trade-off queda encapsulado en `ExternalAgentProvider.processScope()`:

```kotlin
enum class ProcessScope { PER_CONVERSATION, PER_IDE }
```

## 9. Integración con OpenCode

### 9.1 Ciclo de vida del proceso

- Comando: `opencode serve --port 0` (0 = puerto random asignado por SO — se parsea de stdout la línea `opencode listening on http://localhost:<port>`).
- Alternativa: si `settings.opencodeBaseUrl` está configurado, Sweep **no** arranca el proceso y usa esa URL directamente.
- Health: `GET {baseUrl}/project` responde `Project[]` cuando el server está listo.
- Kill: `process.destroy()` al cerrar el proyecto o desactivar el provider.

### 9.2 Endpoints usados

Basado en la spec pública (`specs/project.md` en el repo de OpenCode):

| Operación | Endpoint |
|---|---|
| Descubrir/crear proyecto | `POST /project/init` (body `{ directory: project.basePath }`) → `Project` |
| Crear sesión | `POST /project/:projectID/session` con `{ directory: project.basePath }` → `Session` |
| Listar mensajes de sesión (resume) | `GET /project/:projectID/session/:sessionID/message` |
| Enviar prompt | `POST /project/:projectID/session/:sessionID/message` con body `{ parts: [{type:"text", text:<prompt>}], agent: settings.opencodeAgent, model: {providerID, modelID}? }` |
| Cancelar turn | `POST /project/:projectID/session/:sessionID/abort` |
| Suscribirse a eventos | `GET /event` (SSE, filtrado en cliente por `location.directory`) |
| Permiso | `POST /project/:projectID/session/:sessionID/permission/:permissionID` con `{ decision: "allow"|"deny" }` |

### 9.3 Stream SSE `/event`

Formato SSE típico: `data: { "id": "...", "type": "...", "properties": { ... } }\n\n`.

Tipos que se traducen:

| Tipo OpenCode | → `ExternalAgentEvent` |
|---|---|
| `server.connected` | (log, ignorar) |
| `message.updated` con nueva `Part(type=text)` (delta) | `TextDelta(delta, sessionId)` |
| `message.updated` con `Part(type=tool, state=running)` | `ToolCallStarted(id, name, args)` |
| `message.updated` con `Part(type=tool, state=completed)` | `ToolCallCompleted(id, ok, output)` |
| `message.updated` con `Part(type=step_finish)` y `message.info.status=completed` | `TurnCompleted(sessionId)` |
| `permission.updated` | `PermissionRequested(permissionId, tool, args)` |
| `session.error` | `Error(reason)` |
| `file.edited` | (opcional: notificar al `AgentChangeTrackingService`) |

### 9.4 Autenticación

- OpenCode se autentica una sola vez con `opencode auth login <provider>`. Sweep no lo hace por el usuario.
- Si el server local exige token (versión reciente): se pasa vía `Authorization: Bearer` desde `settings` opcional (`opencodeAuthToken` — se añade solo si el usuario reporta un error 401 durante beta; no en MVP).

## 10. Integración con Codex

### 10.1 Ciclo de vida del proceso

- Comando: `codex app-server --listen stdio://` + `settings.codexExtraArgs.split(" ")` (si no vacío).
- Framing: **line-delimited JSON**. Header `jsonrpc:2.0` **omitido** en el wire (spec explícita en el README del app-server).
- Handshake:
  1. Sweep envía `initialize` (cliente ⇢ server) con `clientCapabilities` y `protocolVersion`.
  2. Server responde con sus `serverCapabilities`.
  3. Sweep queda listo para `thread/start` o `thread/resume`.

### 10.2 Métodos JSON-RPC usados

| Dirección | Método | Uso |
|---|---|---|
| C→S | `initialize` | handshake |
| C→S | `thread/start` | nueva conversación (params: `cwd`, `model`, `approvalPolicy`, `sandbox`) |
| C→S | `thread/resume` | reanudar (`threadId`, opcional `excludeTurns: true`) |
| C→S | `turn/start` | mandar prompt (`threadId`, `input: [{type:"text", text: prompt}]`) |
| C→S | `turn/interrupt` | cancelar |
| S→C (notif) | `turn/started` | inicio del turn (`turnId`) |
| S→C (notif) | `item/*` | eventos incrementales (userMessage, agentMessage, agentReasoning, toolCall, toolCallOutput, error) |
| S→C (notif) | `turn/completed` | fin del turn + usage |
| S→C (request) | `item/tool/call` | (solo si Sweep declaró `dynamicTools` en `thread/start`) — el server pide a Sweep ejecutar un tool. En MVP **no** se declaran `dynamicTools`, así que este RPC no ocurre. Se documenta el hook para el modo host-tools (§17). |
| S→C (request) | `permission/request` (o el equivalente `approval/request` según versión) | aprobación de comandos si `approvalPolicy != "never"` |

### 10.3 Mapping `item/*` → `ExternalAgentEvent`

| `item.type` (Codex) | → evento |
|---|---|
| `agent_message` (delta) | `TextDelta` |
| `agent_reasoning` (delta) | `TextDelta` con `annotations.thinking += delta` |
| `tool_call.started` | `ToolCallStarted(id, name, arguments)` |
| `tool_call.output` con `success=true` | `ToolCallCompleted(id, true, contentItemsAsText)` |
| `tool_call.output` con `success=false` | `ToolCallCompleted(id, false, error)` |
| `user_message` | (echo — ignorar; ya lo tenemos localmente) |
| `error` | `Error(reason)` |

### 10.4 Autenticación

- Codex usa la infraestructura `codex login` (OAuth o `OPENAI_API_KEY`). No se toca desde Sweep. Si al arrancar el proceso hay un error de auth, se propaga como `Error` visible al usuario con un CTA para correr `codex login` en la terminal.

## 11. Contratos / interfaces

### 11.1 `ExternalAgentProvider`

```kotlin
package dev.sweep.assistant.api.external

interface ExternalAgentProvider {
    val id: String                 // "opencode" | "codex"
    val displayName: String
    val processScope: ProcessScope

    fun detectExecutable(): String?          // absolute path o null

    // Ciclo de vida
    suspend fun ensureRunning(project: Project, settings: SweepSettings): ProviderHandle
    suspend fun close(handle: ProviderHandle)

    // Sesión remota
    suspend fun createSession(handle: ProviderHandle, cwd: String, hints: SessionHints): String  // remoteSessionId
    suspend fun resumeSession(handle: ProviderHandle, remoteSessionId: String): ResumeResult

    // Interacción
    suspend fun sendUserMessage(
        handle: ProviderHandle,
        remoteSessionId: String,
        prompt: String,
        images: List<Image> = emptyList(),
    ): Flow<ExternalAgentEvent>

    suspend fun cancelCurrentTurn(handle: ProviderHandle, remoteSessionId: String)
    suspend fun answerPermission(handle: ProviderHandle, remoteSessionId: String, permissionId: String, allow: Boolean)

    // Test connection
    suspend fun testConnection(handle: ProviderHandle): TestResult
}

data class ProviderHandle(val opaque: Any)  // lo que cada provider quiera guardar
data class SessionHints(val agent: String? = null, val model: String? = null)
sealed interface ResumeResult {
    data class Ok(val restoredSessionId: String) : ResumeResult
    data object NotFound : ResumeResult
    data class Failed(val reason: String) : ResumeResult
}
sealed interface TestResult {
    data class Ok(val details: String) : TestResult
    data class Fail(val reason: String) : TestResult
}
enum class ProcessScope { PER_CONVERSATION, PER_IDE }
```

### 11.2 `ExternalAgentEvent`

```kotlin
sealed interface ExternalAgentEvent {
    data class TextDelta(val delta: String, val kind: TextKind = TextKind.CONTENT) : ExternalAgentEvent
    data class ToolCallStarted(val toolCallId: String, val toolName: String, val args: Map<String, Any?>) : ExternalAgentEvent
    data class ToolCallCompleted(val toolCallId: String, val ok: Boolean, val output: String) : ExternalAgentEvent
    data class PermissionRequested(val permissionId: String, val toolName: String, val args: Map<String, Any?>) : ExternalAgentEvent
    data class TurnCompleted(val remoteSessionId: String, val usage: Map<String, Long> = emptyMap()) : ExternalAgentEvent
    data class Error(val reason: String, val recoverable: Boolean) : ExternalAgentEvent
}

enum class TextKind { CONTENT, THINKING }
```

### 11.3 `ExternalAgentChatEngine`

```kotlin
class ExternalAgentChatEngine(
    private val project: Project,
    private val agentSession: SweepAgentSession,
    private val sessionStore: ExternalAgentSessionStore,
    private val registry: ExternalAgentProviderRegistry,
) {
    suspend fun stream(
        conversationId: String,
        finalMessages: List<Message>,
        currentFilePath: String?,
        systemPromptExtras: String,   // lo que el chat cloud llama "system rules"
        onMessageUpdated: (Message) -> Unit,
    )

    suspend fun cancel(conversationId: String)
    suspend fun onProviderChanged(newProviderId: String)
}
```

### 11.4 `ExternalAgentSessionStore`

Contrato mínimo (implementación con la misma SQLite de `ChatHistory`):

```kotlin
interface ExternalAgentSessionStore {
    fun get(conversationId: String): ExternalSessionRow?
    fun put(row: ExternalSessionRow)
    fun delete(conversationId: String)
}

data class ExternalSessionRow(
    val conversationId: String,
    val providerId: String,      // "opencode" | "codex"
    val remoteSessionId: String, // thr_... o sess_...
    val cwd: String,
    val createdAt: Long,
)
```

## 12. Estructura de archivos sugerida

```
src/main/kotlin/dev/sweep/assistant/
├── api/
│   ├── AnthropicClient.kt                        (sin cambios)
│   └── external/                                 (NUEVO)
│       ├── ExternalAgentProvider.kt
│       ├── ExternalAgentEvent.kt
│       ├── ExternalAgentChatEngine.kt
│       ├── ExternalAgentProviderRegistry.kt
│       ├── ExternalAgentSessionStore.kt
│       ├── opencode/
│       │   ├── OpencodeAgentProvider.kt
│       │   ├── OpencodeProcess.kt
│       │   ├── OpencodeHttpClient.kt
│       │   ├── OpencodeProtocolAdapter.kt
│       │   └── OpencodeModels.kt
│       └── codex/
│           ├── CodexAgentProvider.kt
│           ├── CodexProcess.kt
│           ├── CodexJsonRpcClient.kt
│           ├── CodexProtocolAdapter.kt
│           └── CodexModels.kt
├── controllers/
│   └── Stream.kt                                 (modificado, §10 abajo)
├── agent/
│   └── SweepAgentSession.kt                      (añadir `recordExternalCompletion`)
├── services/
│   └── ChatHistory.kt                            (migración de esquema)
├── settings/
│   ├── SweepSettings.kt                          (+ campos §6)
│   └── SweepSettingsConfigurable.kt              (+ pestaña §13.2)
└── components/
    └── SweepConfig.kt                            (delegados)
```

## 13. Cambios requeridos en el código actual

### 13.1 `Stream.kt`

Añadir, antes de la ruta `getConnection("backend/chat", …)`:

```kotlin
val settings = SweepSettings.getInstance()

when (settings.chatProviderId) {
    "opencode", "codex" -> {
        val engine = project.getService(ExternalAgentChatEngine::class.java)
        engine.stream(
            conversationId = currentConversationId,
            finalMessages = finalMessages,
            currentFilePath = effectiveCurrentFilePath,
            systemPromptExtras = /* mismo bloque que hoy pasa al backend */,
            onMessageUpdated = { msg -> this@Stream.onMessageUpdated(msg) },
        )
        currentMarkdownDisplay.stopStreaming()
        return
    }
    "local" -> { /* futuro: local-chat-implementation.md */ }
    else -> { /* cae al flujo cloud actual */ }
}
```

Se mantiene toda la lógica actual de construcción de mensajes/snippets/mentions — el engine sólo recibe los mensajes ya normalizados.

### 13.2 `SweepSettingsConfigurable.kt`

Nueva pestaña **"Chat Provider"**, visible siempre (no oculta en cloud, para permitir A/B):

```
┌ Chat Provider ──────────────────────────────────────────┐
│  Provider:  [▼ Sweep Cloud (default)             ]      │
│             [  Local model                       ]      │
│             [  OpenCode                          ]      │
│             [  Codex                             ]      │
│                                                          │
│  ── OpenCode (visible si Provider = OpenCode) ──         │
│  Executable:   [opencode          ] [Detect] [Test]     │
│  Extra args:   [                  ]                     │
│  Base URL (opt):[http://localhost:… (leave empty to auto-start)]
│  Agent profile:[▼ build] plan / chat                    │
│                                                          │
│  ── Codex (visible si Provider = Codex) ──               │
│  Executable:   [codex             ] [Detect] [Test]     │
│  Extra args:   [                  ]                     │
│  Model:        [(default)         ]                     │
│  Approval:     [▼ on-request] never / on-failure /      │
│                 untrusted                               │
│  Sandbox:      [▼ workspace-write] read-only /          │
│                 danger-full-access                      │
│                                                          │
│  Status: [● connected] · [Test Connection]              │
└──────────────────────────────────────────────────────────┘
```

Comportamiento:

- `Detect` → llama a `provider.detectExecutable()` (busca en PATH via `SystemInfo` + rutas típicas: `~/.local/bin`, `~/.cargo/bin`, `/opt/homebrew/bin`, `%LOCALAPPDATA%\\...`).
- `Test Connection` → arranca el proceso ephemerally (o hace ping HTTP) y reporta.
- Al cambiar `Provider`, no se cierran conversaciones abiertas, pero se notifica que la siguiente entrada se enrutará al nuevo proveedor.

### 13.3 `SweepConfig.kt`

Añadir delegados getters/updaters (patrón actual del archivo).

### 13.4 `SweepAgentSession.kt`

Añadir:

```kotlin
fun recordExternalCompletion(call: CompletedToolCall, boundMessageIndex: Int? = null) {
    // Skip scheduling: writes directly to completedToolCalls and updates the bound Message.
    // Reuses the drain queue used by `enqueueCompletedToolCalls` to keep UI updates single-threaded.
}
```

### 13.5 `ChatHistory.kt`

Migración v+1:

```sql
CREATE TABLE IF NOT EXISTS external_agent_session (
    conversation_id   TEXT PRIMARY KEY,
    provider_id       TEXT NOT NULL,
    remote_session_id TEXT NOT NULL,
    cwd               TEXT NOT NULL,
    created_at        INTEGER NOT NULL
);
```

Reutilizar el mismo `Connection` que ya usa `ChatHistory`. Añadir `ExternalAgentSessionStoreImpl` que llama a `ChatHistory.getInstance(project).connection(...)`.

## 14. Estados de UI necesarios

Reutilizar los actuales; solo se añaden matices:

| Estado | Cómo se muestra |
|---|---|
| Provider *desconectado* al enviar | Snackbar de error + `Message` con `annotations.notification = "Cannot reach OpenCode server. Check Settings → Chat Provider"` |
| Proveedor arrancando | Skeleton "Starting OpenCode…" con spinner de `MarkdownDisplay` |
| Sesión remota reconstruida (thread/resume OK) | Chip informativo "Resumed remote session `thr_1234`" en la parte superior del hilo la primera vez |
| Sesión perdida | Chip warning "Previous remote session not found — started a new one" |
| Turn en curso | El habitual (glow cursor, botón Stop) |
| Permission requested | Diálogo modal con: tool name, args resumidos, botones Allow/Deny/Allow always |
| Tool call ejecutado por el agente | Igual que hoy, pero con badge "external" en el bloque de tool call |
| Tool call fallido en el agente | Igual que hoy con badge de error |
| Turn completado | Final message renderizado normalmente + usage tokens si vino |
| Error del proceso (crash) | Toast + `Message` con reintento manual |

## 15. Manejo de errores

Estrategia: **fallback controlado, jamás datos silenciosamente perdidos**.

| Error | Detección | Respuesta |
|---|---|---|
| Ejecutable no encontrado | `ProcessBuilder` lanza `IOException` | Notificación con CTA "Configure path" que abre Settings → Chat Provider |
| Proceso muere durante turn | `Process.isAlive == false` observado desde reader thread | Marcar `Message` como interrumpido, `providerHandle` → estado `DEAD`, siguiente prompt re-arranca (una vez) |
| Timeout de handshake `initialize` | 30 s | `Error` visible + kill del proceso |
| HTTP 401 (OpenCode) | Interceptor OkHttp | Toast "Run `opencode auth login`" + link |
| Codex auth failure (`authRequired`) | Notificación en `initialize` | Toast "Run `codex login`" |
| SSE desconecta (OpenCode) | Reader lanza `EOFException` | Reintento con backoff exponencial 3 veces; luego error visible |
| Tool call output > 1 MB | Truncar a 100 KB con marca "[truncated]" | Continuar el turn |
| JSON malformado | Log + descartar frame, seguir | Solo abortar si acumula > 5 en 10s |
| Cancelación durante permiso pendiente | Enviar deny automático + interrumpir turn |  |
| Provider changed mid-stream | Bandera atómica en el engine | Cancelar turn en curso, no aplicar los deltas restantes |

Todos los errores fatales del engine se enrutan a `SweepErrorReporter` con `provider=<id>` para triage.

## 16. Persistencia necesaria

1. **SQLite** — tabla `external_agent_session` (§14). Escrita al crear sesión remota, leída al reabrir conversación.
2. **Historial de mensajes** — sin cambios: `ChatHistory.saveChatMessages(conversationId)` sigue guardando `Message[]` en la misma tabla. Los tool calls ejecutados por el agente externo quedan como `CompletedToolCall` normales (con marca `mcpProperties["executor"]=providerId`).
3. **Settings** — `SweepSettings.xml` (7 nuevos campos, §6).
4. **No** se persiste el `remoteSessionId` en el `SessionMessageList` en memoria; se lee siempre del store para evitar drift.
5. **No** se cachean respuestas del agente en Sweep (el proveedor ya persiste su thread).

## 17. Extensibilidad para agregar más proveedores

El diseño está pensado para añadir **Claude Code** y **Cursor** (via su modo ACP) como próximos providers:

1. Crear `ClaudeCodeAgentProvider : ExternalAgentProvider` en `api/external/claudecode/` o `cursor/`.
2. Registrarlo en `ExternalAgentProviderRegistry.registerBuiltIns()`.
3. Añadir campos `claudeCodeCommand`, `claudeCodeExtraArgs`, etc., a `SweepSettings` y un bloque en la pestaña de settings.
4. Reusar `ExternalAgentChatEngine` sin cambios.

Para providers que **sí** son ACP nativos (Cursor `agent acp`, adapter `claude-code-acp`), se puede opcionalmente:

- Introducir una base `AcpBasedAgentProvider` que encapsule el JSON-RPC 2.0 de ACP y expone `sendUserMessage → Flow<ExternalAgentEvent>`, y hacer que Cursor/ClaudeCode extiendan de ella.
- Codex NO usa esa base (su protocolo es "similar pero distinto" — thread/turn/item en lugar de session/prompt/update), y OpenCode tampoco (HTTP).

**Modo host-tools (post-MVP):** cambiar `SessionHints` para incluir `dynamicTools = SweepAgentToolCatalog.export()`. Codex ya soporta esto vía `dynamicTools` en `thread/start` y RPC `item/tool/call` server→client. Para OpenCode se hará vía MCP server local (Sweep expone su catálogo como MCP y OpenCode se conecta a él por su bloque `mcp`).

**MCP bridge (post-MVP):** los MCP servers ya configurados en Sweep (`SweepMcpService`) se pueden exponer al agente externo generando dinámicamente un config parcial (`.opencode.json` con `mcp: { ... }` para OpenCode; parámetro `mcpServers` en `thread/start` para Codex si la versión lo soporta).

## 18. Riesgos técnicos

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| Codex app-server sigue en `alpha` — nombres de métodos y payloads pueden cambiar | Alta | Alto | Fijar rango de versión probado en README; smoke tests al arranque validan métodos disponibles; feature-flag para desactivar Codex si detecta versión incompatible |
| OpenCode API cambia entre releases (`sessions.prompt` v2 vs v1) | Media | Alto | Detección de versión vía `GET /project` (headers) y selección del route apropiado |
| Puerto ocupado en OpenCode | Baja | Bajo | Usar `--port 0` y parsear puerto desde stdout |
| Tool calls con outputs muy grandes saturan UI | Media | Medio | Truncado + collapse por defecto |
| Deadlock en `pendingRequests` de CodexJsonRpcClient si el server muere sin responder | Media | Alto | Todas las promesas expiran a 5 min; watchdog que las cancela cuando el proceso muere |
| Divergencia de cwd entre la sesión remota persistida y el proyecto reabierto en otra ruta | Baja | Medio | Al `resume` comparar `cwd` guardado vs `project.basePath`; si difiere, mostrar warning y ofrecer "recrear sesión" |
| Auth failure poco descriptivo del agente | Media | Bajo | Mapear códigos conocidos a mensajes accionables |
| Sesión Codex acumula memoria (thread largo) | Media | Medio | Botón "Compact / New session" en la UI del chat que llama a `thread/start` limpio |
| MCP + tools duplicados (Sweep MCP + agent-side tools) confunden al modelo | Media | Medio | En MVP no exponemos MCP de Sweep al agente. Documentar. |
| SSE de OpenCode filtra por `location.directory` — si el proyecto está en un symlink, el filter falla | Baja | Medio | Resolver `basePath.toRealPath()` antes de pasarlo |
| Windows: `codex app-server --listen stdio://` puede requerir sintaxis distinta de stdin/stdout | Media | Medio | Probar en CI Windows; documentar limitación si aparece |

## 19. Decisiones pendientes

1. **¿Delegado o host-tools por defecto en Codex?** — MVP: delegado. ¿Cambia esto la percepción del usuario ("mi diff aparece pero yo no lo escribí")? Requiere test con usuarios internos.
2. **¿Un proceso Codex por conversación o compartido?** — MVP: por conversación. Más memoria pero más aislado. Reevaluar cuando >5 conversaciones abiertas.
3. **¿Reemplazar `SweepAgentSession` en modo externo o reutilizarlo como libro contable?** — Este plan propone reutilizarlo (§7.3). Alternativa: bypass total y renderizar tool calls como bloques informativos plain. Trade-off: menos código nuevo vs UI potencialmente confusa (usuario no puede rechazar un tool ya ejecutado por el agente).
4. **¿Selector de modelo/agente en la barra del chat, no solo en Settings?** — Aporta UX; deja `chatProviderId` como configuración por-conversación en el `SessionMessageList` en vez de global. Preferible pero fuera de MVP.
5. **¿Rebranding del setting "Chat Provider"?** — Alternativas: "Agent" / "Backend" / "Coding agent". Preferir el término que el usuario final reconozca.
6. **¿Persistir `remoteSessionId` por-conversación en el XML del proyecto o solo en SQLite?** — SQLite (§14) para consistencia con el resto del historial. Si el usuario borra el DB se pierden, pero el thread sigue viviendo en Codex/OpenCode.
7. **¿Se deshabilita `SweepCommitMessageService` cuando `chatProviderId != sweep-cloud`?** — No, mantiene su propio path (cloud o local según `local-chat-implementation.md`).
8. **Compatibilidad con imágenes** — OpenCode soporta `type: "file"` en `Part`; Codex soporta `type: "inputImage"` en el input. Implementar en MVP o post-MVP? Sugerido: post-MVP (rara vez crítico en chat de código).
9. **¿Registrar `ExternalAgentChatEngine` como servicio de aplicación o de proyecto?** — Servicio de proyecto: necesita `project` para `basePath` y para `SweepAgentSession`. Sí, project-level.

Estas quedan como *TBD* para RFC interna, no bloqueantes para arrancar Fase 1.

## 20. Checklist de implementación paso a paso

### Fase 0 — Preparación (~1 día) — ✅ hecho

- [x] Confirmar versiones testeadas: `codex >= <fijar tras spike>`, `opencode >= <fijar tras spike>`. Anotar en README y en `SweepSettingsConfigurable`.
- [x] Añadir dependencia HTTP+SSE (si no está): Ktor client CIO ya presente en `build.gradle.kts`.
- [x] Añadir dependencia JSON: kotlinx-serialization compartida vía `defaultJson` (utils/RequestUtils.kt).

### Fase 1 — Data models + Settings + skeleton (~2 días) — ✅ hecho

- [x] Añadir 7 campos a `SweepSettings.kt` (`chatProviderId` + OpenCode + Codex).
- [x] Añadir delegados a `SweepConfig.kt`.
- [x] Añadir pestaña **"Chat Provider"** en `SweepChatProviderConfigurable.kt` con: dropdown provider, secciones OpenCode y Codex, botones `Detect` y `Test Connection`.
- [x] Crear paquete `api/external/` con `ExternalAgentProvider.kt`, `ExternalAgentEvent.kt`, `ExternalAgentProviderRegistry.kt`, `ExternalAgentSessionStore.kt`.
- [x] Migración SQLite en `ChatHistory.kt` para `external_agent_session` + `ExternalAgentSessionStoreImpl`.
- [x] Registrar `ExternalAgentChatEngine` como project-level service (con stub para Fase 4).

### Fase 2 — OpenCode provider (~4 días) — ✅ hecho

- [x] `OpencodeProcess.kt`: spawn `opencode serve`, puerto ephemeral pre-asignado, password aleatoria + Basic auth, parse de `opencode server listening on ...`, health check y drain de stdout/stderr.
- [x] `OpencodeHttpClient.kt`: cliente Ktor CIO + SSE parser sobre `GET /event` (session-pinned via `?directory=` y `x-opencode-directory`).
- [x] `OpencodeModels.kt`: DTOs para Session, Prompt, Part, PermissionRequest, envelope SSE (v2 `message.part.delta` + v1 `message.part.updated`).
- [x] `OpencodeProtocolAdapter.kt`: envelope → `List<ExternalAgentEvent>`, con `PartTextTracker` para diffs incrementales y dedupe de tool calls.
- [x] `OpencodeAgentProvider.kt`: `ExternalAgentProvider` completo, `PER_IDE` process scope, runtimes keyed por `(baseUrl, projectDirectory)`.
- [x] `Test Connection` para OpenCode: `provider.testConnection(handle)` hace ping vía `GET /session`.
- [ ] Smoke test manual: prompt "list files in this project", ver eventos y respuesta. (Pendiente de ejecutar en Fase 4/6 una vez el engine esté enganchado a `Stream.start`.)

### Fase 3 — Codex provider (~5 días) — ✅ hecho

- [x] `CodexProcess.kt`: spawn `codex app-server --listen stdio://`, drain de stderr en pooled thread, env inherit desde `EnvironmentUtil` (fallback login shell).
- [x] `CodexJsonRpcClient.kt`: framing NDJSON con `"jsonrpc":"2.0"` omitido, `sendRequest` correlado por id con `CompletableDeferred`, timeouts (5 min por defecto), auto-reject de RPCs server→client no soportados en MVP (permission/request, item/tool/call), watchdog que cancela pendings al morir el proceso.
- [x] `CodexModels.kt`: envelope JSON-RPC, `CodexInitializeParams`, `CodexThreadStart/Resume`, `CodexTurnStart/InterruptParams`, `CodexTurnStarted/Completed`, `CodexUsage`, `CodexMethods`, `CodexItemTypes`.
- [x] `CodexProtocolAdapter.kt`: mapea `turn/started|completed|failed` y `item/started|updated|completed|failed` a `ExternalAgentEvent`. `CodexItemTracker` mantiene last-seen text (para deltas) y dedupe de tool calls; truncado de outputs > 100 KB (plan §15).
- [x] `CodexAgentProvider.kt`: `PER_CONVERSATION`, `CodexRuntime` como map `threadId → CodexSubprocessCtx`, `createSession` = spawn + initialize + thread/start, `resumeSession` = spawn + initialize + thread/resume (con NotFound cuando el server no reconoce el thread), `sendUserMessage` = channelFlow sobre `MutableSharedFlow` de notificaciones que termina en el primer `TurnCompleted`/error, `cancelCurrentTurn` = `turn/interrupt` con fallback a matar el proceso.
- [x] `Test Connection` para Codex: `spawnAndInitialize` y cierre; auth failure se traduce a un mensaje accionable ("Run `codex login` in a terminal").
- [x] Registrar `CodexAgentProvider` en `ExternalAgentProviderRegistry.registerBuiltIns()`.
- [ ] Smoke test manual: prompt en un repo pequeño, ver tool calls y diffs. (Pendiente de ejecutar en Fase 4/6.)

### Fase 4 — Engine + Stream integration (~3 días) — ✅ hecho

- [x] `ExternalAgentChatEngine.kt`: `stream()` resuelve provider + `ensureRunning` + resume/create sesión + drena `Flow<ExternalAgentEvent>` en `Message` incrementales; `cancel()` invoca `provider.cancelCurrentTurn`; `onProviderChanged()` cancela los turns en vuelo cuyo provider cambió.
- [x] `ExternalAgentSessionStoreImpl.kt`: ya existía desde Fase 1 sobre la infra de `ChatHistory`; consumido por el engine.
- [x] `Stream.start()`: rama temprana `when(settings.chatProviderId in {"opencode","codex"})` que delega a `startExternalAgentTurn(...)` (nuevo helper) manteniendo la lógica de mensajes/snippets/mentions intacta.
- [x] `SweepAgentSession.recordExternalCompletion(...)`: escribe directo en `completedToolCalls` sin schedular ejecución, reusando el drain queue existente.
- [x] Cancelación end-to-end: `Stream.stop()` llama `ExternalAgentChatEngine.cancelBlocking(convId)` + cancela `streamingJob`; el engine propaga la cancelación y marca el mensaje como `stopStreaming = "stop"`.
- [x] Reconexión: `resolveRemoteSession()` consulta el store, prueba `resumeSession`, y cae a `createSession` transparentemente si el thread ya no existe.
- [x] Guard adicional: `SweepAgentSession.ingestToolCalls` salta `scheduleIfReady` cuando `mcpProperties["executor"]` está presente, para no re-ejecutar localmente los tool calls del agente externo.
- [ ] Smoke test manual con `chatProviderId=opencode` y `codex` una vez el ecosistema local esté preparado (Fase 6).

### Fase 5 — UX polish (~2 días) — ✅ hecho

- [x] Badge "Executed by …" en tooltips de bloques de tool call cuando `mcpProperties["executor"] != null` (ver `AgentActionBlockDisplay.buildToolCallTooltip`).
- [x] Toasts de error accionables (`codex login`, `opencode auth login`, path no encontrado). Se disparan desde `ExternalAgentChatEngine.surfaceErrorToast` en errores emitidos por el provider y en fallos terminales del engine.
- [x] Diálogo modal Allow/Deny para `PermissionRequested` (`ExternalAgentChatEngine.promptPermission`).
- [x] Botón/API "New remote session": `ExternalAgentChatEngine.newRemoteSession(conversationId)` borra el mapping del store y fuerza `createSession` en el próximo mensaje.
- [ ] Chip informativo "Resumed remote session" / "Started new session" — pendiente (requiere componente visual en el header del chat).
- [ ] Iconos por provider en el dropdown — pendiente (nice-to-have).

### Fase 6 — Tests, docs, release (~2 días) — ✅ hecho (lo esencial)

- [x] Unit tests: `ExternalAgentProtocolTest` cubre traducción de eventos OpenCode y Codex + filtrado por sesión/thread + `ExternalAgentProviderRegistry`.
- [x] Actualizar `README.md`: sección "External Agent Chat (OpenCode / Codex)".
- [x] Actualizar `docs/plans/brainstorm-ideas.md`: marcado como ✅ hecho en el índice y en la fila de OpenCode/Codex.
- [x] Actualizar `plugin.xml` `<change-notes>` y `UpdateChangesNotification.nonCloudContent`.
- [ ] Integration tests con fakes: `FakeCodexProcess` (script bash o Kotlin) que emite frames pre-grabados; `FakeOpencodeServer` (HTTP embebido) — pendiente para un release posterior.
- [ ] Manual QA matrix (§21) — requiere binarios reales.
- [ ] Actualizar `CHANGELOG` — completado vía `plugin.xml` change-notes (no hay `CHANGELOG.md` separado).

### Fase 7 — Post-MVP (backlog)

- [ ] Modo host-tools (Codex `dynamicTools` + Sweep MCP server local).
- [ ] Bridge de MCP servers configurados en Sweep hacia el agente externo.
- [ ] Selector de provider/model por conversación (no global).
- [ ] Auth wizard integrado (opencode auth / codex login).
- [ ] Providers ACP nativos (Cursor, Claude Code).

## 21. Matriz de QA manual mínima

| Escenario | OpenCode | Codex |
|---|---|---|
| Ejecutable no está en PATH | ✓ | ✓ |
| Test Connection OK | ✓ | ✓ |
| Envío de un prompt simple ("hello") | ✓ | ✓ |
| Prompt que dispara `read_file` interno del agente | ✓ | ✓ |
| Prompt que dispara `bash` — con approval on-request | (aprobación en OpenCode) | (aprobación en Codex) |
| Stop en mitad del turn | ✓ | ✓ |
| Reabrir conversación → resume OK | ✓ | ✓ |
| Reabrir conversación con proceso ya muerto | ✓ (auto-restart) | ✓ (auto-restart) |
| Cambiar provider mid-conversation | ✓ (aviso + nueva sesión) | ✓ (aviso + nueva sesión) |
| Ejecutable ausente al mandar mensaje | ✓ (toast + link a settings) | ✓ |
| Crash del proceso durante el turn | ✓ (error + retry) | ✓ |
| Cwd cambia entre re-aperturas | ✓ (warning) | ✓ (warning) |
| Sesión eliminada del sidebar | ✓ (elimina remota) | ✓ (elimina remota) |

## 22. Referencias

- `docs/plans/acp-chat-implementation.md` — motivación de un cliente ACP unificado (deferrable, ver §3).
- `docs/plans/local-chat-implementation.md` — chat con LLM directo (complementario, no conflicto).
- Codex app-server: https://github.com/openai/codex — `codex-rs/app-server/README.md` (`thread/start`, `thread/resume`, `turn/start`, `item/*`, `item/tool/call`).
- OpenCode server spec: https://github.com/anomalyco/opencode — `specs/project.md` y `specs/v2/session.md`.
- OpenCode SDK JS: https://github.com/anomalyco/opencode-sdk-js — utilidades de tipos y stream SSE.
- Codex TS SDK (para inspiración de eventos): https://github.com/openai/codex/tree/main/sdk/typescript.
- `SweepAgentSession.kt` — flujo de `ingestToolCalls`/`awaitToolCalls` que se reutiliza.
- `Stream.kt` — punto de integración.
