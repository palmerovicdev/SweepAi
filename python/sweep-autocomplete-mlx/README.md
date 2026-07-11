# sweep-autocomplete-mlx

MLX-backed next-edit autocomplete server for the Sweep IntelliJ plugin.

Speaks the same NDJSON contract as the upstream `sweep-autocomplete` package
(`POST /backend/next_edit_autocomplete`, `GET /health`), but loads the model
through `mlx_lm` instead of `llama-cpp-python`. Only supported on macOS with
Apple Silicon.

## Run during development

The plugin invokes `uvx sweep-autocomplete-mlx --port <N>`. Until this package
is on PyPI, install it once as a tool so `uvx` resolves the binary:

```bash
uv tool install --editable ./python/sweep-autocomplete-mlx
```

Or run it directly from this folder without going through the plugin:

```bash
MODEL_REPO=Cyanophyte/sweep-next-edit-v2-7B-mlx-8Bit \
  uv run --with mlx-lm --with fastapi --with 'uvicorn[standard]' --with brotli \
  python -m sweep_autocomplete_mlx.server --port 8081
```

## Env vars

- `MODEL_REPO` — HF repo id or local filesystem path to an MLX model
  (default `Cyanophyte/sweep-next-edit-v2-7B-mlx-8Bit`).
- `MODEL_REVISION` — Pin to a specific commit hash for supply-chain hardening
  (default empty; uses latest).
- `MAX_NEW_TOKENS` — maximum generated tokens per request (default `128`).
- `PROMPT_LINES_BEFORE` / `PROMPT_LINES_AFTER` — focused edit window around
  the cursor (default `8` each).
- `PROMPT_INITIAL_CONTEXT_LINES` — broader file context around the cursor
  (default `80` each side).
- `PROMPT_MAX_RETRIEVAL_CHUNKS` / `PROMPT_MAX_FILE_CHUNKS` — cap extra context
  chunks included in the prompt (defaults `3` and `1`).
- `LOG_LEVEL` — `info` (default), `debug`, `warning`, etc.

## Plugin settings

In `Settings → Sweep Autocomplete`:

1. Enable **Use local autocomplete server**.
2. Pick **Launch managed server**.
3. **Backend:** `MLX (Apple Silicon)`.
4. **MLX Repo:** any MLX-converted HF repo id or a local path such as
   `~/Documents/sweep-next-edit-v2-7B-mlx-8Bit`.

The plugin restarts the managed server when backend or model repo change.
