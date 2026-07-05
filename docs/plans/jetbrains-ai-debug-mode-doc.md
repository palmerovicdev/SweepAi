# Debug Mode en plugins de JetBrains: documentación técnica rigurosa

## Resumen ejecutivo

El “debug mode” moderno para JetBrains no suele ser una IA mágica dentro del debugger. Normalmente es un puente MCP/API que le da a un agente externo acceso programático al debugger real del IDE.

El agente deja de adivinar con `print()` o logs insertados a ciegas, y empieza a hacer lo que haría un humano con experiencia:

1. Colocar breakpoints.
2. Arrancar una configuración en modo debug.
3. Esperar una pausa real del programa.
4. Inspeccionar variables.
5. Leer stack traces.
6. Evaluar expresiones.
7. Hacer step over, step into o step out.
8. Repetir hasta aislar la causa.
9. Proponer un fix basado en evidencia runtime.

La conclusión principal es:

> Un buen “AI Debug Mode” no reemplaza el debugger. Lo orquesta.

---

## 1. Qué existe hoy

Hay tres familias reales de soluciones relacionadas con debug en JetBrains.

| Enfoque | Qué hace | Ejemplos |
|---|---|---|
| MCP sobre debugger existente | Expone el debugger de JetBrains a agentes externos como Claude Code, Codex, Cursor, Gemini CLI, etc. | JetBrains MCP Server, Debugger MCP Server |
| Plugin que implementa un debugger nuevo | Crea soporte de debugging para un lenguaje o runtime que JetBrains no soporta directamente. | AutoHotKey debugger sample, CLion debugger stub |
| Plugin que visualiza o analiza estado de debug | Usa la sesión de debug para mostrar mejor variables, objetos o historial. | Visual Debugger |

Desde IntelliJ IDEA 2025.2, JetBrains documenta un MCP Server integrado que permite a clientes externos interactuar con el IDE y el proyecto. En la documentación 2026.1 aparece un bloque específico de Debugger tools provisto por el Debugger MCP toolset plugin.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

El plugin open source más claro encontrado es:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

Ese plugin expone un servidor MCP dentro del IDE y da control programático del debugger: breakpoints, sesiones, variables, stepping, stack, threads y evaluación de expresiones.

---

## 2. Modelo mental correcto

El flujo incorrecto sería:

```text
IA lee código
  ↓
inventa hipótesis
  ↓
mete logs a lo loco
  ↓
reza
```

El flujo correcto es:

```text
IA formula hipótesis
  ↓
coloca breakpoint o tracepoint
  ↓
ejecuta en debug
  ↓
espera pausa real
  ↓
lee estado runtime
  ↓
evalúa expresiones
  ↓
ajusta hipótesis
  ↓
hace step/resume
  ↓
propone fix
```

Ese cambio es la clave.

El plugin no “debuguea con IA” por sí mismo. Le presta al agente herramientas reales del debugger.

---

## 3. Arquitectura típica

```text
AI Agent
  │
  │ MCP / HTTP / JSON-RPC
  ▼
JetBrains Plugin
  │
  ├─ Tool Router
  │    ├─ list_run_configurations
  │    ├─ start_debug_session
  │    ├─ set_breakpoint
  │    ├─ get_stack_trace
  │    ├─ get_variables
  │    ├─ evaluate_expression
  │    └─ step / resume / pause / stop
  │
  ├─ Debug Session Manager
  │
  ├─ Breakpoint Manager
  │
  ├─ Variable / Frame Inspector
  │
  ├─ Expression Evaluator
  │
  └─ Command History / Tool Window
        │
        ▼
IntelliJ Debugger / XDebugger API
        │
        ▼
Debuggee: app, tests, Flutter app, JVM app, JS app, etc.
```

El plugin `hechtcarmel/jetbrains-debugger-mcp-plugin` corre un servidor embebido dentro del IDE y soporta transporte MCP por:

- Streamable HTTP.
- SSE legacy.
- HTTP stateless.

También asigna puertos por IDE para evitar choques cuando hay varias IDEs abiertas.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

---

## 4. Herramientas mínimas que necesita un debug mode útil

Un debug mode real necesita varias categorías de herramientas.

---

### 4.1 Run configuration tools

Sirven para descubrir y arrancar cosas que ya existen en JetBrains.

```text
list_run_configurations
execute_run_configuration
start_debug_session
```

El Debugger MCP Server open source documenta:

- `list_run_configurations`
- `execute_run_configuration`
- `start_debug_session`

`execute_run_configuration` puede ejecutar una configuración en modo `run` o `debug`.

`start_debug_session` arranca una sesión de debug para una run configuration concreta.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/blob/main/USAGE.md

---

### 4.2 Breakpoint tools

Herramientas básicas:

```text
list_breakpoints
set_breakpoint
remove_breakpoint
```

Lo importante es que `set_breakpoint` no debe limitarse a poner un punto rojo. Debe soportar:

```text
file_path
line
condition
log_message
suspend_policy
enabled
temporary
```

El plugin open source soporta:

- Breakpoints simples.
- Breakpoints condicionales.
- Tracepoints usando `log_message` con `suspend_policy: none`.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/blob/main/USAGE.md

En el MCP oficial de JetBrains, `xdebug_set_breakpoint` también modela breakpoints con:

- Condición.
- Log message.
- Log stack.
- Temporalidad.
- Política de suspensión.
- Ownership del breakpoint.

JetBrains advierte que crear exitosamente un breakpoint no garantiza que la condición sea válida. Los errores pueden aparecer luego como eventos del debugger.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

---

### 4.3 Session control tools

Herramientas para mover la ejecución:

```text
resume_execution
pause_execution
step_over
step_into
step_out
run_to_line
stop_debug_session
```

El Debugger MCP Server open source documenta:

- `resume_execution`
- `pause_execution`
- `step_over`
- `step_into`
- `step_out`
- `run_to_line`

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/blob/main/USAGE.md

JetBrains en su MCP oficial lo concentra en `xdebug_control_session`, con acciones como:

- `STEP_INTO`
- `STEP_OVER`
- `STEP_OUT`
- `RESUME`
- `PAUSE`
- `STOP`
- `WAIT_FOR_PAUSE`
- `DRAIN_EVENTS`

Detalle importante: después de `RESUME`, recomiendan llamar `WAIT_FOR_PAUSE`. No se debe asumir que el programa se paró.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

---

### 4.4 State inspection tools

Aquí está la parte más importante:

```text
get_debug_session_status
get_stack_trace
get_variables
get_source_context
list_threads
get_value_by_path
evaluate_expression
```

La herramienta más importante del plugin open source es `get_debug_session_status`, porque devuelve en una sola llamada:

- Estado de sesión.
- Ubicación actual.
- Breakpoint alcanzado.
- Stack.
- Variables.
- Source context.
- Thread actual.

Eso reduce roundtrips y le da al agente un snapshot real del fallo.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/blob/main/USAGE.md

JetBrains documenta herramientas equivalentes:

- `xdebug_get_debugger_status`
- `xdebug_get_stack`
- `xdebug_get_frame_values`
- `xdebug_get_threads`
- `xdebug_get_value_by_path`
- `xdebug_evaluate_expression`

También repite una regla crítica: no reutilizar `frameIndex` viejo después de `RESUME`, `STEP_*` o cambios de ubicación, porque el frame puede haber cambiado.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

---

## 5. State machine correcta

Un debug mode serio debe tratar la sesión como una máquina de estados.

```text
NO_SESSION
  └─ start_debug_session
      ▼
RUNNING
  ├─ wait_for_pause
  ├─ pause
  └─ stop
      ▼
PAUSED
  ├─ get_stack
  ├─ get_variables
  ├─ evaluate_expression
  ├─ step_over / step_into / step_out
  ├─ run_to_line
  ├─ resume
  └─ stop
      ▼
STOPPED
```

Regla de oro:

```text
get_variables, get_stack, evaluate_expression y step_* requieren sesión pausada.
```

El plugin open source devuelve errores como:

- `Session Not Found`
- `File Not Found`
- `Not Paused`
- `Breakpoint Error`
- `Evaluation Error`

Antes de llamar herramientas que requieren pausa conviene comprobar el estado con `get_debug_session_status`.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/blob/main/USAGE.md

JetBrains también define precondiciones. Por ejemplo:

- `STEP_*` requiere sesión suspendida.
- `RESUME` requiere sesión suspendida.
- `xdebug_get_frame_values` requiere sesión suspendida.
- `xdebug_get_stack` requiere sesión suspendida.
- `xdebug_get_threads` requiere sesión suspendida.
- `xdebug_get_value_by_path` requiere sesión suspendida.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

---

## 6. Cómo se conecta con JetBrains por dentro

Hay dos casos.

---

### Caso A: exponer el debugger existente

Este es el camino correcto para un plugin tipo AI Debug Mode si JetBrains ya sabe debuguear ese lenguaje.

No se implementa un debugger desde cero. Se usa la sesión existente del IDE.

El plugin debe:

```text
1. Encontrar run configurations.
2. Ejecutar una configuración en debug.
3. Obtener sesiones activas.
4. Controlar sesión actual.
5. Leer stack, frames y variables.
6. Evaluar expresiones.
7. Crear y remover breakpoints.
8. Reportar todo al agente por MCP.
```

Este enfoque lo usan:

- JetBrains MCP Server oficial.
- Debugger MCP Server open source.

El segundo dice que funciona contra cualquier IDE JetBrains que soporte XDebugger, con compatibilidad probada en:

- IntelliJ IDEA.
- PyCharm.
- WebStorm.
- GoLand.
- RustRover.
- Android Studio.
- PhpStorm.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

---

### Caso B: implementar un debugger nuevo

Aquí sí se implementa soporte de debugging propio.

Para crear un debugger custom en la plataforma IntelliJ, la ruta oficial es implementar:

- `XDebugProcess`
- `ProgramRunner`
- `XLineBreakpointType`
- Opcionalmente `XAttachDebuggerProvider`

El stub oficial de CLion lo resume así.

Fuente:

- https://github.com/JetBrains/clion-debugger-plugin-stub

El tutorial de AutoHotKey explica el flujo central:

1. El `ProgramRunner` debe aceptar `DefaultDebugExecutor.EXECUTOR_ID`.
2. Luego debe llamar `XDebuggerManager.startSession`.
3. Dentro de un `XDebugProcessStarter` se crea el `XDebugProcess`.

Fuente:

- https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

---

## 7. Piezas internas de XDebugger

---

### 7.1 `XDebugProcess`

Es el objeto que recibe comandos como:

- Resume.
- Pause.
- Step over.
- Step into.
- Step out.
- Run to position.
- Stop.

También expone:

- Console.
- Breakpoint handlers.
- Editors provider.
- Estado del proceso.

Fuente:

- https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

---

### 7.2 `XDebugSession`

Es la sesión que el IDE conoce.

El debugger le notifica eventos como:

- Llegué a breakpoint.
- Llegué a posición.
- Terminé.
- Este breakpoint es válido.
- Este breakpoint es inválido.

Cuando el debuggee se detiene en breakpoint, se llama `session.breakpointReached`.

Cuando se detiene por otra razón, se llama `session.positionReached`.

Fuente:

- https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

---

### 7.3 `XSuspendContext`

Representa el estado cuando el programa está pausado.

De aquí salen:

- Threads.
- Stack frames.
- Variables locales.
- Variables globales.
- Contexto de ejecución.

Para un AI Debug Mode, esto es oro puro. Es donde el plugin convierte estado runtime en JSON para el agente.

Fuente:

- https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

---

### 7.4 `XBreakpointHandler`

Maneja registrar y desregistrar breakpoints.

Métodos clave:

- `registerBreakpoint`
- `unregisterBreakpoint`

También se manejan estados como:

- Breakpoint verificado.
- Breakpoint inválido.

Fuente:

- https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

---

### 7.5 `XDebuggerEditorsProvider`

Se usa para evaluación de expresiones y completion dentro del evaluator.

Si se quiere que `evaluate_expression` sea decente, esta pieza importa.

Fuente:

- https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

---

## 8. Qué hace un agente cuando debuguea

Un flujo robusto sería:

```text
1. Entender el bug report.
2. Buscar entrypoint o test reproducible.
3. Listar run configurations.
4. Poner breakpoints cerca de la hipótesis.
5. Arrancar debug.
6. Esperar pausa.
7. Leer estado completo:
   - archivo
   - línea
   - método
   - stack
   - variables
   - source context
   - thread
8. Evaluar expresiones específicas.
9. Hacer step over, step into o step out según hipótesis.
10. Comparar valor esperado vs valor real.
11. Repetir hasta localizar causa.
12. Proponer fix.
13. Ejecutar build o test.
14. Limpiar breakpoints del agente.
```

La documentación del Debugger MCP Server open source define un Typical Debugging Flow similar:

1. Set breakpoints.
2. Start debugging.
3. Wait for pause.
4. Inspect state.
5. Evaluate or modify.
6. Navigate.
7. Repeat.
8. Clean up.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/blob/main/USAGE.md

---

## 9. Tracepoints

Un tracepoint es un breakpoint que no pausa.

Sirve para loguear estado sin tocar el código.

Ejemplo conceptual:

```json
{
  "file_path": "/project/lib/src/user_service.dart",
  "line": 42,
  "log_message": "userId={userId}, state={state}",
  "suspend_policy": "none"
}
```

En JetBrains MCP oficial, un breakpoint con `isLogMessage` o `isLogStack` y `suspendPolicy=NONE` funciona como tracepoint.

También permite drenar outputs con `DRAIN_EVENTS`.

JetBrains aclara que esos eventos de tracepoint y breakpoint errors actualmente están soportados por debuggers JVM, como Java/Kotlin. En otros backends pueden venir vacíos aunque el breakpoint esté configurado.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

Implicación para Flutter/Dart:

No se debe asumir que todos los tracepoint outputs funcionan igual que en JVM. El debugger puede parar, inspeccionar y evaluar si el backend lo soporta, pero los eventos enriquecidos pueden depender del plugin/debugger backend.

Un plugin serio debe probar capacidades y degradar elegantemente.

---

## 10. Evaluación de expresiones

`evaluate_expression` es extremadamente útil porque permite preguntar cosas como:

```text
cart.items.length
userRepository.count()
state.currentUser?.id
```

Pero también es peligroso, porque evaluar expresiones puede ejecutar métodos reales dentro del proceso debugueado.

El Debugger MCP Server open source documenta modos de seguridad:

- `Unrestricted`
- `Default blocklist`
- `Read-only`

También advierte que no es un sandbox real, sino un filtro best-effort antes de pasar la expresión al evaluator del debugger.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

Recomendación:

```text
Default: read-only.
Permitir mutation/method calls solo con confirmación o flag explícito.
Loguear toda expresión ejecutada.
Bloquear file/network/process/system/reflection por defecto.
```

---

## 11. Diseño recomendado para un plugin JetBrains con Debug Mode

---

### 11.1 Tool API mínima

```text
debug.listRunConfigurations
debug.startSession(configurationName)
debug.listSessions
debug.getStatus(sessionId?)
debug.setBreakpoint(filePath, line, condition?, trace?, suspendPolicy?)
debug.removeBreakpoint(breakpointId)
debug.listBreakpoints(filePath?)
debug.resume(sessionId)
debug.pause(sessionId)
debug.waitForPause(sessionId, timeoutMs)
debug.stepOver(sessionId)
debug.stepInto(sessionId)
debug.stepOut(sessionId)
debug.runToLine(sessionId, filePath, line)
debug.getStack(sessionId)
debug.getFrameValues(sessionId, frameIndex, depth)
debug.getValueByPath(sessionId, frameIndex, path, depth)
debug.evaluate(sessionId, frameIndex, expression, safetyMode)
debug.stopSession(sessionId)
```

---

### 11.2 Tool response ideal

Toda respuesta debería incluir:

```json
{
  "ok": true,
  "sessionId": "App#123",
  "state": "paused",
  "location": {
    "file": "lib/src/foo.dart",
    "line": 42,
    "method": "calculateTotal"
  },
  "nextRecommendedCalls": [
    "debug.getFrameValues",
    "debug.getStack",
    "debug.evaluate"
  ],
  "warnings": []
}
```

`nextRecommendedCalls` parece un detalle menor, pero ayuda mucho a los agentes.

JetBrains usa una idea similar de “Next call” en varias herramientas del debugger MCP.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

---

## 12. Manejo de breakpoints del agente

Un punto serio: no se deben mezclar breakpoints humanos con breakpoints del agente como si fueran la misma cosa.

El plugin debería marcar ownership:

```text
owner = user
owner = agent
owner = temporary
```

El MCP oficial de JetBrains documenta ownership para breakpoints y dice que las operaciones exitosas pueden marcar el breakpoint como `agent` usando `mcpBreakpointMarker`.

También permite remover breakpoints filtrando por owner.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

Recomendación:

```text
- El agente solo borra sus propios breakpoints por defecto.
- Para borrar breakpoints del usuario, necesita permiso explícito.
- Los breakpoints temporales se limpian al terminar la sesión.
- Cada breakpoint debe guardar razón/hypothesis.
```

Ejemplo:

```json
{
  "breakpointId": "bp_42",
  "owner": "agent",
  "reason": "Check why selectedTemplate becomes null before save",
  "createdAt": "2026-06-28T09:00:00-04:00"
}
```

---

## 13. Command history y auditoría

Un debug mode con IA necesita una bitácora visible.

El Debugger MCP Server open source agrega un tool window llamado “Debugger MCP Server” con:

- Estado del servidor.
- URL.
- Proyecto actual.
- Historial de comandos.
- Timestamp.
- Tool name.
- Status.
- Parámetros y resultados expandibles.
- Duración.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

Eso es correcto. Sin historial, el agente hace cosas dentro del debugger y el usuario no puede auditar qué pasó.

Auditoría recomendada:

```text
- timestamp
- client/agent
- tool
- args resumidos
- result resumido
- duration
- sessionId
- breakpointId
- error/warning
```

---

## 14. Plugins y proyectos open source relevantes

---

### 14.1 Debugger MCP Server

Repositorio:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

Es el más relevante para AI Debug Mode.

Características:

- Open source.
- Kotlin.
- MCP server embebido.
- Herramientas de debug completas.
- Soporta varios clientes MCP.

Estudiar para:

```text
- MCP transport dentro del IDE.
- Tool routing.
- Debug session abstraction.
- Command history.
- Breakpoint ownership.
- Evaluate safety modes.
```

---

### 14.2 JetBrains MCP Server oficial

Documentación:

- https://www.jetbrains.com/help/idea/mcp-server.html

No es principalmente una referencia open source, pero su documentación es muy valiosa porque muestra el diseño oficial de herramientas, precondiciones y “next call”.

Estudiar para:

```text
- Nombres de herramientas oficiales xdebug_*.
- Precondiciones.
- sessionId handling.
- wait_for_pause.
- frameIndex freshness.
- breakpoint ownership.
```

---

### 14.3 AutoHotKey Debugger sample

Artículo:

- https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

Buen material para entender cómo implementar un debugger nuevo usando XDebugger API.

Explica:

- `ProgramRunner`
- `XDebuggerManager.startSession`
- `XDebugProcess`
- `XDebugSession`
- `XSuspendContext`
- `XBreakpointHandler`
- `XDebuggerEditorsProvider`

Estudiar para:

```text
- Custom debugger desde cero.
- Wrapping de un protocolo externo como DBGP.
- Mapping debuggee events -> XDebugSession.
```

---

### 14.4 CLion Debugger Plugin Stub

Repositorio:

- https://github.com/JetBrains/clion-debugger-plugin-stub

Ejemplo oficial/educativo de JetBrains para debugger custom en CLion.

Explica dos rutas:

1. API oficial de IntelliJ con `XDebugProcess`, `ProgramRunner`, `XLineBreakpointType`.
2. API CIDR/CLion más baja y menos estable.

Estudiar para:

```text
- Estructura mínima de plugin debugger.
- Run configuration.
- Step / pause / resume fake.
- Evaluación simple.
```

---

### 14.5 Visual Debugger

Repositorio:

- https://github.com/timKraeuter/VisualDebugger

No es AI Debug Mode, pero es útil para entender cómo consumir estado de debug y visualizarlo.

Características:

- Representa variables de stack frame como diagramas de objetos.
- Muestra cambios entre pasos.
- Tiene historial para retroceder visualmente.

Estudiar para:

```text
- Extracción de variables.
- Visualización de objetos.
- Historial de estados runtime.
- UX encima del debugger.
```

---

### 14.6 AgentBridge

Repositorio:

- https://github.com/catatafishen/agentbridge

Proyecto reciente que declara herramientas MCP para JetBrains, incluyendo:

- Debugging.
- Tests.
- Coverage.
- Git.
- Breakpoints.
- Exception breakpoints.
- Stepping.
- Variables.
- Evaluación.
- Console output.

Lo marcaría como interesante para revisar, pero no como base principal sin auditar código y madurez.

---

## 15. Diseño de prompt o skill para el agente

JetBrains documenta una skill `/ij-debugger` para guiar clientes externos sobre cuándo usar debugger tools, qué evidencia runtime recoger y cómo manejar breakpoints/sesiones.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

Un skill bueno debe decirle al agente:

```text
Cuando haya bug runtime:
1. No edites primero.
2. Reproduce.
3. Usa debugger si hay run configuration.
4. Pon breakpoints mínimos.
5. Espera pausa.
6. Recoge stack + variables + source context.
7. Evalúa expresiones pequeñas.
8. No ejecutes expresiones con side effects salvo permiso.
9. Mantén hipótesis explícitas.
10. Limpia breakpoints del agente.
11. Propón fix basado en evidencia runtime.
```

Plantilla recomendada:

```md
# Debugger behavior

Use debugger tools when:
- The failure is runtime-dependent.
- The stack trace is insufficient.
- The bug involves state, timing, async flow, null values, race conditions, or unexpected branches.
- Adding logs would require noisy code changes.

Rules:
- Prefer temporary agent-owned breakpoints.
- Always call get_debugger_status before session-scoped operations.
- After resume, call wait_for_pause.
- Do not reuse frameIndex after stepping/resuming.
- Use read-only evaluation by default.
- Collect evidence before editing.
- Summarize evidence before proposing code changes.
```

---

## 16. Riesgos y limitaciones

---

### 16.1 No todos los debuggers soportan lo mismo

JetBrains dice que algunos eventos, como breakpoint errors y tracepoint outputs, actualmente se reportan solo en debuggers JVM.

En otros backends pueden venir vacíos.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

---

### 16.2 Evaluar expresiones puede cambiar estado

`evaluate_expression` puede llamar métodos.

El plugin open source lo reconoce y por eso incluye safety modes, pero también advierte que no es un sandbox real.

Fuente:

- https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

---

### 16.3 Sesiones y frames se vuelven stale

Después de step, resume o run-to-line, el frame puede cambiar.

JetBrains insiste en no reutilizar `frameIndex` cacheado después de cambios de ubicación.

Fuente:

- https://www.jetbrains.com/help/idea/mcp-server.html

---

### 16.4 El agente necesita workflow, no solo tools

Darle herramientas al agente sin reglas claras es peligroso.

Un debug mode real necesita:

- Tooling.
- Precondiciones.
- Seguridad.
- Auditoría.
- Limpieza automática.
- Skill/prompt de comportamiento.
- Fallbacks cuando el backend no soporta algo.

---

## 17. Recomendación de implementación

Para implementar algo tipo Debug Mode en un plugin JetBrains, la vía buena es:

```text
1. No implementar un debugger nuevo si JetBrains ya debuguea ese stack.
2. Exponer el debugger existente vía MCP.
3. Diseñar tools pequeñas, explícitas y state-aware.
4. Usar ownership para breakpoints.
5. Forzar wait_for_pause después de resume.
6. Devolver snapshots ricos: stack + variables + source context.
7. Proteger evaluate_expression.
8. Añadir command history visible.
9. Añadir una skill/prompt de uso.
10. Degradar features por backend: JVM, Dart, JS, Python, etc.
```

---

## 18. Veredicto

Esto es totalmente viable y ya hay referencias reales.

Para copiar arquitectura:

1. Debugger MCP Server:
   - https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin

2. JetBrains MCP Debugger toolset:
   - https://www.jetbrains.com/help/idea/mcp-server.html

Para entender bajo nivel:

3. AutoHotKey Debugger sample:
   - https://fornever.me/en/posts/2026-01-04.intellij-debugger.html

4. CLion Debugger Plugin Stub:
   - https://github.com/JetBrains/clion-debugger-plugin-stub

Para UX y visualización de estado:

5. Visual Debugger:
   - https://github.com/timKraeuter/VisualDebugger

6. AgentBridge:
   - https://github.com/catatafishen/agentbridge

La arquitectura recomendada no es meter IA dentro del debugger como una caja negra. Es exponer el debugger real como herramientas controladas, auditables y seguras para que el agente pueda razonar con evidencia runtime.

Ese es el camino serio.
