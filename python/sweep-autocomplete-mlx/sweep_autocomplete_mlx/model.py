"""
Thin wrapper around mlx-lm load/generate.

Loads once at startup and exposes a single ``generate_completion`` call that
returns the post-processed (stop-token-trimmed) model output for a prompt.
"""

from __future__ import annotations

import logging
from pathlib import Path
import threading
from typing import Callable, Optional

from .prompt import DEFAULT_MAX_NEW_TOKENS, STOP_TOKENS

logger = logging.getLogger(__name__)


class MlxModel:
    def __init__(self, model_repo: str, model_revision: str = "") -> None:
        self._model_repo = model_repo
        self._model_revision = model_revision
        self._lock = threading.Lock()
        self._ready = False
        self._model = None
        self._tokenizer = None

    @property
    def is_ready(self) -> bool:
        return self._ready

    @property
    def model_repo(self) -> str:
        return self._model_repo

    def _load_target(self) -> str:
        expanded = Path(self._model_repo).expanduser()
        if expanded.exists():
            return str(expanded.resolve())
        return self._model_repo

    def load(self) -> None:
        from mlx_lm import load  # imported here so server can boot before mlx is verified

        load_target = self._load_target()
        logger.info("Loading MLX model %s ...", load_target)
        kwargs = {}
        if self._model_revision:
            kwargs["revision"] = self._model_revision
        model, tokenizer = load(load_target, **kwargs)
        self._model = model
        self._tokenizer = tokenizer
        self._ready = True
        logger.info("MLX model loaded (revision=%s).", self._model_revision or "default")

    def generate_completion(
        self,
        prompt: str,
        max_new_tokens: int = DEFAULT_MAX_NEW_TOKENS,
        should_stop: Optional[Callable[[], bool]] = None,
    ) -> str:
        if not self._ready:
            raise RuntimeError("MLX model not ready")

        from mlx_lm import stream_generate  # local import keeps the cli importable without mlx

        # Stream generation so autocomplete can stop as soon as a custom stop
        # marker appears, instead of always waiting for max_new_tokens.
        with self._lock:
            if should_stop and should_stop():
                return ""
            chunks = []
            for response in stream_generate(
                self._model,
                self._tokenizer,
                prompt=prompt,
                max_tokens=max_new_tokens,
            ):
                if should_stop and should_stop():
                    logger.info("Stopping stale MLX generation early.")
                    break
                chunks.append(response.text)
                text = "".join(chunks)
                if any(stop in text for stop in STOP_TOKENS):
                    break
            else:
                text = "".join(chunks)

        for stop in STOP_TOKENS:
            idx = text.find(stop)
            if idx != -1:
                text = text[:idx]
        return text


_singleton: Optional[MlxModel] = None
_singleton_lock = threading.Lock()


def get_model(model_repo: str, model_revision: str = "") -> MlxModel:
    global _singleton
    with _singleton_lock:
        if _singleton is None or _singleton.model_repo != model_repo or _singleton._model_revision != model_revision:
            # Drop reference to old model so it can be GC'd before loading the new one
            _singleton = None
            _singleton = MlxModel(model_repo, model_revision)
        return _singleton
