"""
FastAPI server that exposes the same NDJSON contract as ``sweep-autocomplete``
(``POST /backend/next_edit_autocomplete``) but is backed by mlx-lm.

The plugin only ever cares about:
- ``GET  /health``                       -> 200 when the model is loaded
- ``POST /backend/next_edit_autocomplete`` -> streams one NDJSON line with the
   ``NextEditAutocompleteResponse`` shape defined in EditAutocompleteModels.kt

Run with::

    MODEL_REPO=Cyanophyte/sweep-next-edit-v2-7B-mlx-8Bit \\
        uvx sweep-autocomplete-mlx --port 8081
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import threading
import time
import uuid
from typing import Any, AsyncIterator, Dict, List, Optional

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse

from .model import get_model
from .prompt import (
    DEFAULT_MAX_NEW_TOKENS,
    FileChunk,
    build_prompt,
    is_pure_insertion_above_cursor,
)

DEFAULT_MODEL_REPO = "Cyanophyte/sweep-next-edit-v2-7B-mlx-8Bit"
DEFAULT_PORT = 8081

logger = logging.getLogger("sweep-autocomplete-mlx")


def _maybe_decompress(body: bytes, encoding: str) -> bytes:
    """The plugin may send the body Brotli-compressed."""
    enc = (encoding or "").lower().strip()
    if enc == "br":
        try:
            import brotli
        except ImportError as e:
            raise RuntimeError("Brotli body received but 'brotli' not installed") from e
        return brotli.decompress(body)
    return body


def _to_chunks(raw: Optional[List[Dict[str, Any]]]) -> List[FileChunk]:
    if not raw:
        return []
    out: List[FileChunk] = []
    for c in raw:
        if not isinstance(c, dict):
            continue
        out.append(FileChunk(file_path=c.get("file_path", ""), content=c.get("content", "")))
    return out


def _empty_response(elapsed_ms: int) -> Dict[str, Any]:
    """Shape required by NextEditAutocompleteResponse — empty completion path."""
    return {
        "start_index": 0,
        "end_index": 0,
        "completion": "",
        "confidence": 0.0,
        "autocomplete_id": str(uuid.uuid4()),
        "elapsed_time_ms": elapsed_ms,
        "completions": [],
    }


def _build_response(
    *,
    new_block: str,
    block_start: int,
    block_end: int,
    elapsed_ms: int,
) -> Dict[str, Any]:
    ac_id = str(uuid.uuid4())
    completion_obj = {
        "start_index": block_start,
        "end_index": block_end,
        "completion": new_block,
        "confidence": 1.0,
        "autocomplete_id": ac_id,
    }
    return {
        # legacy/duplicate fields (still serialized to keep backwards-compat)
        "start_index": block_start,
        "end_index": block_end,
        "completion": new_block,
        "confidence": 1.0,
        "autocomplete_id": ac_id,
        "elapsed_time_ms": elapsed_ms,
        "completions": [completion_obj],
    }


def create_app(model_repo: str) -> FastAPI:
    app = FastAPI(title="sweep-autocomplete-mlx")
    state_lock = threading.Lock()
    load_error: Dict[str, Optional[str]] = {"msg": None}

    model = get_model(model_repo)

    def _load_in_background() -> None:
        try:
            model.load()
        except Exception as e:  # pragma: no cover - surfaced in /health
            logger.exception("Failed to load MLX model: %s", e)
            with state_lock:
                load_error["msg"] = str(e)

    threading.Thread(target=_load_in_background, daemon=True).start()

    @app.get("/health")
    def health() -> Response:
        if load_error["msg"]:
            return JSONResponse(
                status_code=503,
                content={"status": "error", "error": load_error["msg"]},
            )
        if not model.is_ready:
            return JSONResponse(
                status_code=503,
                content={"status": "loading", "model_repo": model.model_repo},
            )
        return JSONResponse(content={"status": "ok", "model_repo": model.model_repo})

    @app.post("/backend/next_edit_autocomplete")
    async def next_edit_autocomplete(request: Request) -> StreamingResponse:
        raw_body = await request.body()
        try:
            decoded = _maybe_decompress(raw_body, request.headers.get("content-encoding", ""))
            payload = json.loads(decoded.decode("utf-8"))
        except Exception as e:
            logger.warning("Bad request body: %s", e)
            return StreamingResponse(
                _stream_error(f"Bad request body: {e}"),
                media_type="application/x-ndjson",
                status_code=400,
            )

        async def _generate() -> AsyncIterator[bytes]:
            async for line in _handle_request(payload):
                yield line

        return StreamingResponse(_generate(), media_type="application/x-ndjson")

    async def _stream_error(msg: str) -> AsyncIterator[bytes]:
        yield (json.dumps({"status": "error", "error": msg}) + "\n").encode("utf-8")

    async def _handle_request(payload: Dict[str, Any]) -> AsyncIterator[bytes]:
        if payload.get("ping"):
            yield (json.dumps(_empty_response(0)) + "\n").encode("utf-8")
            return

        if load_error["msg"]:
            yield (
                json.dumps({"status": "error", "error": f"model load failed: {load_error['msg']}"}) + "\n"
            ).encode("utf-8")
            return

        if not model.is_ready:
            yield (
                json.dumps({"status": "error", "error": "model still loading"}) + "\n"
            ).encode("utf-8")
            return

        try:
            file_path = payload.get("file_path", "")
            file_contents = payload.get("file_contents", "")
            cursor_position = int(payload.get("cursor_position", 0))
            recent_changes = payload.get("recent_changes", "") or ""
            changes_above_cursor = bool(payload.get("changes_above_cursor", False))
            file_chunks = _to_chunks(payload.get("file_chunks"))
            retrieval_chunks = _to_chunks(payload.get("retrieval_chunks"))

            built = build_prompt(
                file_path=file_path,
                file_contents=file_contents,
                cursor_position=cursor_position,
                recent_changes=recent_changes,
                retrieval_chunks=retrieval_chunks,
                file_chunks=file_chunks,
                changes_above_cursor=changes_above_cursor,
            )

            t0 = time.monotonic()
            completion = model.generate_completion(
                built.prompt, max_new_tokens=DEFAULT_MAX_NEW_TOKENS
            )
            elapsed_ms = int((time.monotonic() - t0) * 1000)

            # Model output continues from the prefill. The new code block is prefill + output.
            new_block = built.prefill + completion

            if is_pure_insertion_above_cursor(
                built.code_block, new_block, built.relative_cursor
            ):
                logger.info("Rejected pure-insertion-above-cursor completion.")
                yield (json.dumps(_empty_response(elapsed_ms)) + "\n").encode("utf-8")
                return

            if new_block.strip() == built.code_block.strip():
                yield (json.dumps(_empty_response(elapsed_ms)) + "\n").encode("utf-8")
                return

            resp = _build_response(
                new_block=new_block,
                block_start=built.block_start_index,
                block_end=built.block_end_index,
                elapsed_ms=elapsed_ms,
            )
            yield (json.dumps(resp) + "\n").encode("utf-8")
        except Exception as e:
            logger.exception("Generation failed: %s", e)
            yield (json.dumps({"status": "error", "error": str(e)}) + "\n").encode("utf-8")

    return app


def main() -> None:
    parser = argparse.ArgumentParser(prog="sweep-autocomplete-mlx")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument(
        "--model-repo",
        default=os.environ.get("MODEL_REPO", DEFAULT_MODEL_REPO),
        help="HuggingFace repo id of an MLX-converted model.",
    )
    parser.add_argument("--log-level", default=os.environ.get("LOG_LEVEL", "info"))
    args = parser.parse_args()

    logging.basicConfig(
        level=args.log_level.upper(),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    import uvicorn

    app = create_app(args.model_repo)
    uvicorn.run(app, host=args.host, port=args.port, log_level=args.log_level)


if __name__ == "__main__":
    main()
