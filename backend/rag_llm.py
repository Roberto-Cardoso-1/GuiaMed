"""Offline base LLM for RAG. No OCR adapter, downloads or input/output logging."""

from __future__ import annotations

import hashlib
import json
import os
import re
import threading
import time
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL_ID = "HuggingFaceTB/SmolLM2-135M-Instruct"
MODEL_REVISION = "12fd25f77366fa6b3b4b768ec3050bf629380bac"


class GenerationUnavailable(RuntimeError):
    pass


class GenerationTimeout(RuntimeError):
    pass


class ContextTooLong(RuntimeError):
    pass


class GenerationFailed(RuntimeError):
    pass


@dataclass(frozen=True)
class Generation:
    raw_response: str
    input_tokens: int
    output_tokens: int
    model: str = MODEL_ID
    revision: str = MODEL_REVISION
    load_ms: float = 0.0
    timed_out: bool = False
    backend: str = "fake"
    llm_called: bool = False
    constrained: bool = False


@dataclass(frozen=True)
class Prompt:
    version: str
    executable: str
    sha256: str


def load_prompt(version: str) -> Prompt:
    if version not in ("v1", "v2"):
        raise ValueError("prompt_version must be v1 or v2")
    text = (ROOT / "prompts" / f"{version}.md").read_text(encoding="utf-8-sig")
    sections = re.findall(
        r"<!-- EXECUTABLE_PROMPT_BEGIN -->\s*(.*?)\s*<!-- EXECUTABLE_PROMPT_END -->",
        text, re.DOTALL,
    )
    if len(sections) != 1 or not sections[0].strip():
        raise ValueError("Prompt must have one nonempty executable section")
    executable = sections[0].strip()
    return Prompt(version, executable, hashlib.sha256(executable.encode("utf-8")).hexdigest())


def prompt_messages(prompt: Prompt, query: str, chunks: list[dict[str, str]]) -> list[dict[str, str]]:
    # Escape chat delimiters inside untrusted data, including literal special tokens.
    def escape(value: str) -> str:
        return value.replace("<", "\\u003c").replace(">", "\\u003e")
    data = escape(json.dumps({"consulta": query, "chunks": chunks}, ensure_ascii=False))
    return [{"role": "system", "content": prompt.executable}, {"role": "user", "content": data}]


def literal_passages(content: str) -> tuple[str, ...]:
    # Whole sentences only: decimal dots and abbreviations/numbers are not
    # split arbitrarily. The whole chunk remains an admissible longer answer.
    sentences = re.split(r"(?<=[.!?])\s+(?=[A-ZÁÉÍÓÚÂÊÔÃÕÇ])", content.strip())
    # Never isolate a later qualifier (e.g. a maximum dose without its initial
    # schedule). Candidates are complete prefixes of the source, not fragments.
    return tuple(dict.fromkeys(" ".join(sentences[:end]) for end in range(1, len(sentences) + 1)))


def allowed_sequences(tokenizer, messages, max_tokens: int) -> tuple[tuple[int, ...], ...]:
    chunks = json.loads(messages[1]["content"])["chunks"]
    sequences = []
    for chunk in chunks:
        passages = (chunk["texto"],) if chunk["fonte"].endswith("#posologia") else literal_passages(chunk["texto"])
        for passage in passages:
            text = f"{passage} Fonte: {chunk['fonte']}."
            tokens = tokenizer.encode(text, add_special_tokens=False) + [tokenizer.eos_token_id]
            if len(tokens) <= max_tokens:
                sequences.append(tuple(tokens))
    if not sequences:
        raise ContextTooLong("no_complete_passage_fits")
    return tuple(dict.fromkeys(sequences))


def token_constraint(sequences, input_length):
    def allowed(_batch_id, ids):
        prefix = tuple(ids[input_length:].tolist())
        next_tokens = {sequence[len(prefix)] for sequence in sequences
                       if len(prefix) < len(sequence) and sequence[:len(prefix)] == prefix}
        if not next_tokens:
            raise RuntimeError("No grounded continuation")
        return sorted(next_tokens)
    return allowed


class RagLlm:
    """Lazy singleton model; bounded, serialized generation in the caller's thread."""

    def __init__(self) -> None:
        self._load_lock = threading.Lock()
        self._generation_lock = threading.Lock()
        self._tokenizer = None
        self._model = None
        self._failure: str | None = None

    def _ensure_loaded(self) -> float:
        with self._load_lock:
            if self._model is not None:
                return 0.0
            if self._failure:
                raise GenerationUnavailable(self._failure)
            started = time.perf_counter()
            try:
                cache = Path(os.environ.get("HF_HOME") or ROOT.parent / ".tools" / "huggingface")
                snapshot = cache / "hub" / "models--HuggingFaceTB--SmolLM2-135M-Instruct" / "snapshots" / MODEL_REVISION
                for filename in ("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json"):
                    if not (snapshot / filename).is_file():
                        raise FileNotFoundError(filename)
                import torch
                from transformers import AutoModelForCausalLM, AutoTokenizer

                torch.set_num_threads(4)
                tokenizer = AutoTokenizer.from_pretrained(snapshot, local_files_only=True)
                tokenizer.pad_token = tokenizer.eos_token
                model = AutoModelForCausalLM.from_pretrained(
                    snapshot, local_files_only=True, torch_dtype=torch.float32, use_safetensors=True,
                ).eval()
                self._tokenizer, self._model = tokenizer, model
            except Exception as error:
                # Cache failure and disclose type only; dependency errors can contain input.
                self._failure = type(error).__name__
                raise GenerationUnavailable(self._failure) from None
            return (time.perf_counter() - started) * 1000

    def generate(self, messages: list[dict[str, str]], *, timeout_ms: int = 15000) -> Generation:
        if timeout_ms <= 0:
            raise ValueError("timeout_ms must be positive")
        # Loading is measured separately. Token preparation and generation share
        # a deadline; CPU prefill/token operations are not preemptible.
        load_ms = self._ensure_loaded()
        deadline = time.monotonic() + timeout_ms / 1000
        if not self._generation_lock.acquire(timeout=timeout_ms / 1000):
            raise GenerationTimeout("busy")
        try:
            import torch
            from transformers import StoppingCriteriaList

            tokenizer, model = self._tokenizer, self._model
            ids = tokenizer.apply_chat_template(messages, add_generation_prompt=True, return_tensors="pt")
            input_tokens = ids.shape[1]
            max_new_tokens = 128
            sequences = allowed_sequences(tokenizer, messages, max_new_tokens)
            if input_tokens + max_new_tokens > min(2048, model.config.max_position_embeddings):
                raise ContextTooLong("context_limit")
            if time.monotonic() >= deadline:
                raise GenerationTimeout("preparation")
            with torch.inference_mode():
                try:
                    output = model.generate(
                        input_ids=ids, attention_mask=torch.ones_like(ids),
                        max_new_tokens=max_new_tokens, do_sample=False, pad_token_id=tokenizer.pad_token_id,
                        stopping_criteria=StoppingCriteriaList([lambda *_args, **_kwargs: time.monotonic() >= deadline]),
                        prefix_allowed_tokens_fn=token_constraint(sequences, input_tokens),
                    )
                except Exception:
                    raise GenerationFailed("model_generate_failed") from None
            tokens = output[0, input_tokens:]
            return Generation(
                tokenizer.decode(tokens, skip_special_tokens=True), input_tokens, len(tokens),
                load_ms=load_ms, timed_out=time.monotonic() >= deadline,
                backend="transformers", llm_called=True,
                constrained=True,
            )
        finally:
            self._generation_lock.release()


@lru_cache(maxsize=1)
def get_rag_llm() -> RagLlm:
    return RagLlm()
