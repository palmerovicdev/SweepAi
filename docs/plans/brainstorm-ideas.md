# Brainstorm: Ideas para Sweep AI JetBrains Plugin

> Basado en el estado actual del proyecto y los planes ya documentados en `docs/plans/`.

## Estado Actual

**Sweep AI JetBrains Plugin** — asistente de IA para IDEs JetBrains con:

- Chat completo con streaming, tool calls, agent loop
- Autocomplete local con múltiples backends (MLX, external, managed)
- Sistema de agentes con 25 herramientas
- MCP Client para herramientas externas
- Commit messages generados por IA
- Settings UI rica
- Python autocomplete server (MLX)

### Planes ya documentados

| Plan | Archivo |
|---|---|
| Chat Local + Commit Local | `local-chat-implementation.md` |
| Chat via ACP (Agent Client Protocol) | `acp-chat-implementation.md` |
| AI Debug Mode | `jetbrains-ai-debug-mode-doc.md` |

---

## I. Planes Prioritarios (ya documentados)

### Chat Local con Modelo Local
Usar modelos locales (Ollama, LM Studio, etc.) vía OpenAI-compatible API para chat y commits, eliminando dependencia del backend cloud.

| Fase | Descripción |
|---|---|
| Fase 1 | Data models + settings fields |
| Fase 2 | LocalChatClient (SSE, tool calls, streaming) |
| Fase 3 | LocalAgentOrchestrator (agent loop local) |
| Fase 4 | Reutilizar para commit messages |
| Fase 5 | Polish: test connection, badge, errores |

### ACP Chat — Agentes Externos
Conectar el chat con agentes externos via Agent Client Protocol (JSON-RPC 2.0 sobre stdio).

| Agente | Estado ACP | Prioridad |
|---|---|---|
| Cursor | Nativo (`agent acp`) | Alta |
| Claude Code | Vía `claude-code-acp` | Alta |
| Codex | Adapter necesario (app-server protocol) | Media |
| OpenCode | Adapter necesario (HTTP API) | Media |

### AI Debug Mode
Exponer el debugger de JetBrains como herramientas MCP para que el agente pueda: poner breakpoints, step, inspeccionar variables, evaluar expresiones.

---

## II. Autocomplete (mejoras)

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **Multiline autocomplete** | Completar bloques/funciones completas en vez de una línea | Medio |
| **Context-aware autocomplete** | Usar símbolos del proyecto, imports, tipos para mejores sugerencias | Medio |
| **Autocomplete por lenguaje** | Modelos especializados por lenguaje (ya hay MLX para Python, agregar más) | Bajo |
| **Inline suggestions cycling** | Ciclar entre múltiples sugerencias con Ctrl+Space | Bajo |
| **Autocomplete on demand** | Trigger manual con shortcut dedicado | Bajo |
| **Snippet autocomplete** | Completar con snippets del proyecto | Medio |

---

## III. Chat & UX

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **Multimodal Chat** | Pegar imágenes, diagramas, screenshots como input | Medio |
| **Chat por archivo** | Chat contextual asociado al archivo activo (como Cursor) | Medio |
| **Composer mode** | Panel lateral para ediciones multi-archivo sin historial de chat | Alto |
| **Plan/Apply split** | Planificar cambios, mostrar diff, luego aplicar con revisión | Medio |
| **Diff preview** | Mostrar cambios como diff unificado antes de aplicar | Medio |
| **Chat Templates** | Prompts predefinidos: refactor, tests, fix bugs, explain | Bajo |
| **Conversation branching** | Crear ramas desde cualquier punto de la conversación | Alto |
| **Undo para cambios de IA** | Revertir cambios aplicados por el agente | Medio |
| **Chat search** | Buscar en historial de conversaciones | Bajo |
| **Quick actions toolbar** | Botones de acción rápida en el chat (explain, refactor, fix) | Bajo |

---

## IV. Commits & Git

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **AI Commit PR Review** | Revisar PRs automáticamente (ReviewPRAction existe, expandir) | Medio |
| **AI Changelog** | Generar changelogs automáticos desde commits | Bajo |
| **Commit templates** | Distintos formatos: conventional, emoji, brief | Bajo |
| **Git-aware context** | El agente entiende diff entre ramas, blame, logs | Medio |
| **Automatic branching** | Crear rama automática para cada tarea antes de editar | Bajo |
| **Staged diff analysis** | Analizar cambios staged y sugerir mensaje de commit | Bajo |

---

## V. MCP & Extensibilidad

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **MCP Server embebido** | Sweep como servidor MCP para otros agentes externos | Alto |
| **MCP tools marketplace** | UI para descubrir, instalar y configurar MCP servers | Alto |
| **MCP tools desde settings** | Configurar MCP servers sin tocar JSON | Medio |
| **OpenCode bridge** | Integración directa con OpenCode como backend | Medio |

---

## VI. Debug Mode (post-MVP)

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **Auto-bisect de tests** | El agente encuentra qué commit introdujo un bug | Alto |
| **Test debugger** | Debuggear tests específicos | Medio |
| **Memory/heap inspector** | Inspeccionar memoria, objetos en heap | Alto |
| **Conditional breakpoints UI** | Agregar breakpoints condicionales desde el chat | Medio |
| **Tracepoints** | Breakpoints que no pausan, solo loguean estado | Medio |
| **Hot reload integration** | Recargar cambios sin reiniciar sesión de debug | Alto |

---

## VII. Project Intelligence

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **Project index persistente** | Indexar el proyecto completo para búsquedas rápidas | Alto |
| **Code map / dependency graph** | Visualizar dependencias entre clases/módulos | Alto |
| **Architecture rules** | Definir reglas que el agente debe seguir al editar | Medio |
| **Tech debt detection** | Identificar code smells, duplicación, complejidad | Alto |
| **Automated documentation** | Generar/actualizar documentación desde código cambiado | Medio |
| **Code ownership map** | Mapa de quién escribió cada parte del código | Bajo |

---

## VIII. Testing

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **AI test generator** | Generar tests automáticos para código seleccionado | Alto |
| **Test impact analysis** | Identificar qué tests ejecutar según cambios | Alto |
| **Test flakiness detector** | Detectar tests flaky y sugerir fixes | Alto |
| **Snapshot testing** | Generar/actualizar snapshots automáticamente | Medio |

---

## IX. Performance & Enterprise

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **Caching de respuestas** | Cachear respuestas del LLM para consultas repetidas | Medio |
| **Offline mode** | Funcionalidad completa sin conexión usando modelos locales | Medio |
| **Self-hosted dashboard** | Panel web para monitorear uso, costos y modelos | Alto |
| **Team settings sync** | Compartir settings entre miembros del equipo | Medio |
| **Audit logging** | Registrar todas las acciones del agente para compliance | Medio |
| **Multi-project support** | Trabajar en múltiples proyectos simultáneamente | Medio |

---

## X. Features Diferenciadoras

| Idea | Descripción | Esfuerzo |
|---|---|---|
| **AI pair programmer** | Modo driver/navigator con la IA | Alto |
| **Live Share AI** | Asistente compartido en sesiones colaborativas | Alto |
| **Code reviews automáticos** | Revisar código al hacer commit o push | Medio |
| **Refactoring wizard** | Multi-step refactors guiados con previsualización | Alto |
| **Learning mode** | La IA explica el código mientras navegas | Bajo |
| **Onboarding assistant** | Guía para nuevos desarrolladores en el proyecto | Medio |
| **Natural language queries** | "Find all places where we handle null pointers" | Medio |
| **Voice commands** | Control por voz para acciones comunes | Bajo |
| **Context badges** | Indicadores visuales de qué contexto tiene el agente | Bajo |

---

## Orden Recomendado

```
Fase 1 (inmediata)
├── Chat Local + Commit Local   ← plan listo
├── Multiline autocomplete
├── Chat Templates
└── Commit templates

Fase 2 (corto plazo)
├── ACP Chat — Cursor/Claude Code   ← plan listo
├── Diff preview
├── Plan/Apply split
├── Chat por archivo
└── Quick actions toolbar

Fase 3 (mediano plazo)
├── AI Debug Mode   ← plan listo
├── MCP Server embebido
├── MCP tools marketplace
├── Test generator
└── Architecture rules

Fase 4 (largo plazo)
├── Project index persistente
├── Code map / dependency graph
├── Tech debt detection
├── Self-hosted dashboard
├── Team settings sync
├── Live Share AI
└── Voice commands
```
