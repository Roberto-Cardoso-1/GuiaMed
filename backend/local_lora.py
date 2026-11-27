"""Manifest-verified real-catalog inference and legacy export helpers."""

from __future__ import annotations

import hashlib
import logging
import json
import time
from contextvars import ContextVar
import os
import threading
from pathlib import Path
from typing import Callable

PROJECT_DIR = Path(__file__).resolve().parents[1]
MODEL_REVISION = "12fd25f77366fa6b3b4b768ec3050bf629380bac"
MODEL_CACHE_NAME = "models--HuggingFaceTB--SmolLM2-135M-Instruct"
ADAPTER_DIR = PROJECT_DIR / "evidencias" / "ia" / "finetuning_v2" / "adapter"
ADAPTER_SHA256 = "8289b6de69f2e44c3686ae8dfdfe6eecb7a9599427dc40209935deea58cac6d8"
DEFAULT_REAL_ADAPTER = PROJECT_DIR / "evidencias" / "ia" / "catalog_real_active" / "adapter"
MAX_OCR_CHARS = 384
MAX_NEW_TOKENS = 16
PROMPT_PREFIX = "Identifique o nome comercial do medicamento do catalogo real no OCR. Responda apenas o nome.\nOCR: "
GENERATION_DEADLINE: ContextVar[float] = ContextVar("generation_deadline", default=float("inf"))


def generation_expired(*args, **kwargs) -> bool:
    return time.monotonic() >= GENERATION_DEADLINE.get()


logger = logging.getLogger("guiamed.lora")


def local_snapshot() -> Path:
    hf_home = Path(os.environ.get("HF_HOME") or PROJECT_DIR.parent / ".tools" / "huggingface")
    snapshot = hf_home / "hub" / MODEL_CACHE_NAME / "snapshots" / MODEL_REVISION
    for filename in ("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json"):
        if not (snapshot / filename).is_file():
            raise FileNotFoundError(f"Snapshot local incompleto: {snapshot / filename}")
    return snapshot


def load_merged_model(threads: int = 4, adapter_dir: Path | None = None):
    snapshot = local_snapshot()
    selected = adapter_dir or ADAPTER_DIR
    weights = selected / "adapter_model.safetensors"
    expected_hash = ADAPTER_SHA256
    if adapter_dir is not None:
        manifest = json.loads((selected / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("promotion") != "approved":
            raise ValueError("Adapter experimental nao promovido")
        if manifest["prompt_prefix"] != PROMPT_PREFIX or manifest["revision"] != MODEL_REVISION:
            raise ValueError("Adapter incompativel com prompt ou revisao")
        evaluation_path = selected / manifest["evaluation"]
        evaluation_bytes = evaluation_path.read_bytes()
        if hashlib.sha256(evaluation_bytes).hexdigest() != manifest["evaluation_sha256"]:
            raise ValueError("Evaluation hash mismatch")
        evaluation = json.loads(evaluation_bytes)
        if (evaluation["positive_correct"] / max(1, evaluation["positive_total"]) < .8
                or evaluation["negative_false_accepts"] != 0
                or not all(evaluation[key] for key in ("safety_ok", "recipe_ok", "long_ok"))):
            raise ValueError("Evaluation does not meet promotion criteria")
        catalog = Path(__file__).parent / "data" / "dcb_medicamentos.json"
        if hashlib.sha256(catalog.read_bytes()).hexdigest() != manifest["catalog_sha256"]:
            raise ValueError("Catalog changed since evaluation")
        expected_hash = manifest["sha256"]
    if hashlib.sha256(weights.read_bytes()).hexdigest() != expected_hash:
        raise ValueError("SHA-256 inesperado para o adapter selecionado")

    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    torch.set_num_threads(threads)
    tokenizer = AutoTokenizer.from_pretrained(snapshot, local_files_only=True)
    tokenizer.pad_token = tokenizer.eos_token
    base = AutoModelForCausalLM.from_pretrained(
        snapshot, local_files_only=True, torch_dtype=torch.float32, use_safetensors=True,
    )
    model = PeftModel.from_pretrained(base, selected, local_files_only=True)
    with torch.no_grad():
        merged = model.merge_and_unload(safe_merge=True).eval()
    return tokenizer, merged


class LocalLora:
    def __init__(self, generate: Callable[[str], str]) -> None:
        self._generate = generate
        self.last_result = "not-called"
        self._generation_lock = threading.Lock()

    @classmethod
    def load(cls, gguf_path: Path | None, threads: int, adapter_dir: Path | None = None) -> LocalLora:
        if adapter_dir is None:
            raise ValueError("LoRA v2 legado incompativel; configure adapter real avaliado e promovido")
        if gguf_path is not None:
            raise ValueError("GGUF legado sem manifesto de catalogo real compativel")
        tokenizer, model = load_merged_model(threads, adapter_dir)
        return cls.from_model(tokenizer, model)

    @classmethod
    def from_model(cls, tokenizer, model) -> LocalLora:
        """Same bounded generation for evaluation and production loading."""
        import torch
        from transformers import StoppingCriteriaList

        def generate_fp32(prompt: str) -> str:
            inputs = tokenizer(prompt, return_tensors="pt", add_special_tokens=False)
            with torch.inference_mode():
                output = model.generate(
                    **inputs, max_new_tokens=MAX_NEW_TOKENS, do_sample=False,
                    pad_token_id=tokenizer.pad_token_id,
                    stopping_criteria=StoppingCriteriaList([generation_expired]),
                )
            generated = output[0, inputs.input_ids.shape[1]:]
            return tokenizer.decode(generated, skip_special_tokens=True)

        return cls(generate_fp32)

    def suggest(self, texto_ocr: str) -> str | None:
        if len(texto_ocr) > MAX_OCR_CHARS or not self._generation_lock.acquire(blocking=False):
            self.last_result = "busy-or-oversized"
            return None
        try:
            if generation_expired():
                self.last_result = "deadline"
                return None
            self.last_result = "generated"
            answer = self._generate(f"{PROMPT_PREFIX}{texto_ocr}\nNome:").strip() or None
            if generation_expired():
                self.last_result = "deadline"
                return None
            return answer
        except Exception as error:
            # Exceções da biblioteca podem incluir dados da entrada.
            self.last_result = "generation-error"
            logger.warning("Falha na inferência LoRA (%s)", type(error).__name__)
            return None
        finally:
            self._generation_lock.release()
