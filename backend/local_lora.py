"""Inferência opcional do LoRA v2 a partir de artefatos locais."""

from __future__ import annotations

import hashlib
import logging
import os
import threading
from pathlib import Path
from typing import Callable

PROJECT_DIR = Path(__file__).resolve().parents[1]
MODEL_REVISION = "12fd25f77366fa6b3b4b768ec3050bf629380bac"
MODEL_CACHE_NAME = "models--HuggingFaceTB--SmolLM2-135M-Instruct"
ADAPTER_DIR = PROJECT_DIR / "evidencias" / "ia" / "finetuning_v2" / "adapter"
ADAPTER_SHA256 = "8289b6de69f2e44c3686ae8dfdfe6eecb7a9599427dc40209935deea58cac6d8"
MAX_OCR_CHARS = 768
MAX_NEW_TOKENS = 16
PROMPT_PREFIX = "Identifique o nome comercial do medicamento ficticio no OCR. Responda apenas o nome.\nOCR: "
logger = logging.getLogger("guiamed.lora")


def local_snapshot() -> Path:
    hf_home = Path(os.environ.get("HF_HOME") or PROJECT_DIR.parent / ".tools" / "huggingface")
    snapshot = hf_home / "hub" / MODEL_CACHE_NAME / "snapshots" / MODEL_REVISION
    for filename in ("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json"):
        if not (snapshot / filename).is_file():
            raise FileNotFoundError(f"Snapshot local incompleto: {snapshot / filename}")
    return snapshot


def load_merged_model(threads: int = 4):
    snapshot = local_snapshot()
    weights = ADAPTER_DIR / "adapter_model.safetensors"
    if hashlib.sha256(weights.read_bytes()).hexdigest() != ADAPTER_SHA256:
        raise ValueError("SHA-256 inesperado para o adapter LoRA v2")

    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    torch.set_num_threads(threads)
    tokenizer = AutoTokenizer.from_pretrained(snapshot, local_files_only=True)
    base = AutoModelForCausalLM.from_pretrained(
        snapshot, local_files_only=True, torch_dtype=torch.float32, use_safetensors=True,
    )
    model = PeftModel.from_pretrained(base, ADAPTER_DIR, local_files_only=True)
    with torch.no_grad():
        merged = model.merge_and_unload(safe_merge=True).eval()
    return tokenizer, merged


class LocalLora:
    def __init__(self, generate: Callable[[str], str]) -> None:
        self._generate = generate
        self._generation_lock = threading.Lock()

    @classmethod
    def load(cls, gguf_path: Path | None, threads: int) -> LocalLora:
        if gguf_path is not None:
            if not gguf_path.is_file() or gguf_path.suffix.lower() != ".gguf":
                raise FileNotFoundError(f"GGUF local indisponível: {gguf_path}")
            from llama_cpp import Llama

            model = Llama(model_path=str(gguf_path), n_ctx=1024, n_threads=threads, verbose=False)

            def generate_gguf(prompt: str) -> str:
                tokens = model.tokenize(prompt.encode("utf-8"), add_bos=False, special=False)
                completion = model(tokens, max_tokens=MAX_NEW_TOKENS, temperature=0.0)
                return completion["choices"][0]["text"]

            return cls(generate_gguf)

        tokenizer, model = load_merged_model(threads)
        import torch

        def generate_fp32(prompt: str) -> str:
            inputs = tokenizer(prompt, return_tensors="pt", add_special_tokens=False)
            with torch.inference_mode():
                output = model.generate(
                    **inputs, max_new_tokens=MAX_NEW_TOKENS, do_sample=False,
                    pad_token_id=tokenizer.pad_token_id,
                )
            generated = output[0, inputs.input_ids.shape[1]:]
            return tokenizer.decode(generated, skip_special_tokens=True)

        return cls(generate_fp32)

    def suggest(self, texto_ocr: str) -> str | None:
        if len(texto_ocr) > MAX_OCR_CHARS or not self._generation_lock.acquire(blocking=False):
            return None
        try:
            return self._generate(f"{PROMPT_PREFIX}{texto_ocr}\nNome:").strip() or None
        except Exception as error:
            # Exceções da biblioteca podem incluir dados da entrada.
            logger.warning("Falha na inferência LoRA (%s)", type(error).__name__)
            return None
        finally:
            self._generation_lock.release()
