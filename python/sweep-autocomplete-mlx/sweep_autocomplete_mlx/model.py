"""
Thin wrapper around mlx-lm load/generate.

Loads once at startup and exposes a single ``generate_completion`` call that
returns the post-processed (stop-token-trimmed) model output for a prompt.
"""

from __future__ import annotations

import logging
import threading
from typing import Optional

from .prompt import DEFAULT_MAX_NEW_TOKENS, STOP_TOKENS

logger = logging.getLogger(__name__)


class MlxModel:
    def __init__(self, model_repo: str) -> None:
        self._model_repo = model_repo
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

    def load(self) -> None:
        from mlx_lm import load  # imported here so server can boot before mlx is verified

        logger.info("Loading MLX model %s ...", self._model_repo)
        model, tokenizer = load(self._model_repo)
        self._model = model
        self._tokenizer = tokenizer
        self._ready = True
        logger.info("MLX model loaded.")

    def generate_completion(
        self,
        prompt: str,
        max_new_tokens: int = DEFAULT_MAX_NEW_TOKENS,
    ) -> str:
        if not self._ready:
            raise RuntimeError("MLX model not ready")

        from mlx_lm import generate  # local import keeps the cli importable without mlx

        # mlx-lm has no native stop-string support, so we generate then truncate.
        # Greedy (temp=0) matches the reference inference.py.
        with self._lock:
            text = generate(
                self._model,
                self._tokenizer,
                prompt=prompt,
                max_tokens=max_new_tokens,
                verbose=False,
            )

        if isinstance(text, tuple):  # some mlx-lm versions return (text, info)
            text = text[0]
        if not isinstance(text, str):
            text = str(text)

        for stop in STOP_TOKENS:
            idx = text.find(stop)
            if idx != -1:
                text = text[:idx]
        return text


_singleton: Optional[MlxModel] = None
_singleton_lock = threading.Lock()


def get_model(model_repo: str) -> MlxModel:
    global _singleton
    with _singleton_lock:
        if _singleton is None or _singleton.model_repo != model_repo:
            _singleton = MlxModel(model_repo)
        return _singleton
