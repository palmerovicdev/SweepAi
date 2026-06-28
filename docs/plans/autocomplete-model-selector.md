# Autocomplete: Model Selector + External Endpoint

## Objetivo

Desde la UI de settings (Tools > Sweep Autocomplete) el usuario puede:

1. **Elegir qué modelo de Hugging Face** se descarga para el servidor local managed (`MODEL_REPO` / `MODEL_FILENAME`)
2. **Conectar a un servidor ya corriendo** (`http://host:port`) en vez de que el plugin gestione uno

---

## Archivos a modificar

### 1. `SweepSettings.kt` — 3 nuevos campos

```kotlin
var autocompleteLocalModelRepo: String = "sweepai/sweep-next-edit-0.5B"
var autocompleteLocalModelFilename: String = "sweep-next-edit-0.5b.q8_0.gguf"
var autocompleteExternalUrl: String = ""
```

Persistencia automática vía `PersistentStateComponent` (XML).

### 2. `SweepConfig.kt` (~line 1038) — 6 nuevos wrappers

```kotlin
fun getAutocompleteLocalModelRepo(): String
fun updateAutocompleteLocalModelRepo(repo: String)
fun getAutocompleteLocalModelFilename(): String
fun updateAutocompleteLocalModelFilename(filename: String)
fun getAutocompleteExternalUrl(): String
fun updateAutocompleteExternalUrl(url: String)
```

Todos delegan a `SweepSettings.getInstance()`.

### 3. `LocalAutocompleteServerManager.kt` — cambios de lógica

| Método | Cambio |
|---|---|
| `getServerUrl()` | Si `autocompleteExternalUrl` no es blank → retornarla; si no → `http://localhost:{port}` |
| `ensureServerRunning()` | Si externalUrl → solo health check, no arranca proceso |
| `startServer()` | early return si externalUrl no es blank |
| `startServerProcess()` | Setear `MODEL_REPO` y `MODEL_FILENAME` env vars en `pb.environment()` |
| `stopServer()` | solo mata proceso si no hay externalUrl |
| `restartServer()` | solo reinicia si no hay externalUrl |
| (nuevo) `isManagedMode()` | `return autocompleteExternalUrl.isBlank()` |

En `startServerProcess()`:

```kotlin
pb.environment()["MODEL_REPO"] = SweepSettings.getInstance().autocompleteLocalModelRepo
pb.environment()["MODEL_FILENAME"] = SweepSettings.getInstance().autocompleteLocalModelFilename
```

### 4. `SweepSettingsConfigurable.kt` — nuevo UI

Sección "Local server" modificada:

```
☐ Use local autocomplete server

  ◉ Launch managed server   ○ Connect to existing server   ← ButtonGroup + RadioButtons

  ┌─ Managed panel ───────────────────────────┐
  │ Port:         [8081               ▼]      │
  │ Model Repo:   [sweepai/sweep-next-edit...] │
  │ Model File:   [sweep-next-edit-0.5b.q...] │
  └────────────────────────────────────────────┘

  ┌─ External panel ─────────────────────────┐  ← visible solo si "Connect to existing"
  │ Server URL:   [http://localhost:1234    ] │
  │ ⚠ Invalid URL format                     │  ← label rojo si validación falla
  └───────────────────────────────────────────┘
```

- **Radio buttons** con `ButtonGroup`, switchean visibilidad de los paneles vía `isVisible`
- **Validación URL**: regex `^https?://.+:\d+(/.*)?$` — si inválida, el label rojo aparece y `isModified()` retorna false
- **`isModified()`**: chequea todos los campos incluyendo radio button selection
- **`apply()`**: aplica todos los campos, y si cambió el modo (managed↔external, o URL/port/model), notifica settings changed

### 5. `SweepConfig.kt` Advanced tab (~line 4780) — sin cambios

Se deja como está. El usuario configura todo desde Settings > Sweep Autocomplete.

---

## Flujo de datos

```
Settings UI (SweepSettingsConfigurable)
  ↓ apply()
SweepSettings (XML persistence)
  ↓
LocalAutocompleteServerManager
  ├─ isManagedMode() → externalUrl.isBlank()
  ├─ startServerProcess() → setea MODEL_REPO + MODEL_FILENAME env → uvx sweep-autocomplete
  └─ getServerUrl() → externalUrl ?: "http://localhost:$port"
  ↓
AutocompleteIpResolverService.getBaseUrl()
  └─ LocalAutocompleteServerManager.getInstance().getServerUrl()
```

---

## Comportamiento por modo

| | Managed mode (externalUrl = "") | External mode (externalUrl != "") |
|---|---|---|
| `getServerUrl()` | `http://localhost:<port>` | La URL exacta del setting |
| ¿Arranca proceso? | Sí, con `uvx sweep-autocomplete` + env vars | No |
| `stopServer()` | Mata el proceso hijo | No-op |
| Health check | POST a `http://localhost:<port>` | POST a la external URL |

---

## Consideraciones

- **Validación URL**: solo en el UI, no en runtime. Si alguien pone una URL inválida via archivo XML directo, el health check fallará naturalmente.
- **Sin dropdown de modelos**: dos `JTextField` simples para repo y filename, con placeholders.
- **SweepConfig Advanced tab**: se deja intacto para no duplicar lógica UI. Opcionalmente se puede agregar un label que redirija al settings panel.
- **Transición managed→external**: al hacer apply, si el servidor managed estaba corriendo, se detiene. No se inicia nada nuevo hasta el próximo request.
- **Transición external→managed**: al hacer apply, si había un server managed detenido, se arranca con `ensureServerRunning()`.
