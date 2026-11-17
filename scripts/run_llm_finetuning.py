"""Fine-tuning LoRA local de um LLM pré-treinado com OCR fictício."""

import hashlib
import json
import os
import random
import shutil
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")

import torch
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer, set_seed

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "evidencias" / "ia" / "finetuning"
MODEL_ID = "HuggingFaceTB/SmolLM2-135M-Instruct"
MODEL_REVISION = "12fd25f77366fa6b3b4b768ec3050bf629380bac"
SEED = 20261117


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def prompt(example):
    return (
        "Identifique o nome comercial do medicamento ficticio no OCR. "
        "Responda apenas o nome.\nOCR: " + example["input"] + "\nNome:"
    )


def prepare(tokenizer, example):
    prefix = tokenizer.encode(prompt(example), add_special_tokens=False)
    target = tokenizer.encode(" " + example["expected"] + tokenizer.eos_token, add_special_tokens=False)
    if len(prefix) + len(target) > 192:
        raise ValueError("Exemplo excede limite de tokens")
    return prefix + target, [-100] * len(prefix) + target


def batch_tensors(examples, tokenizer):
    rows = [prepare(tokenizer, example) for example in examples]
    length = max(len(row[0]) for row in rows)
    return {
        "input_ids": torch.tensor([ids + [tokenizer.pad_token_id] * (length - len(ids)) for ids, _ in rows]),
        "attention_mask": torch.tensor([[1] * len(ids) + [0] * (length - len(ids)) for ids, _ in rows]),
        "labels": torch.tensor([labels + [-100] * (length - len(labels)) for _, labels in rows]),
    }


def evaluate(model, tokenizer, test):
    model.eval()
    started = time.perf_counter()
    predictions = []
    losses = []
    with torch.inference_mode():
        for example in test:
            tensors = batch_tensors([example], tokenizer)
            losses.append(model(**tensors).loss.item())
            encoded = tokenizer(prompt(example), return_tensors="pt", add_special_tokens=False)
            generated = model.generate(
                **encoded, max_new_tokens=16, do_sample=False,
                pad_token_id=tokenizer.pad_token_id,
            )
            answer = tokenizer.decode(generated[0, encoded.input_ids.shape[1]:], skip_special_tokens=True)
            answer = answer.strip().split("\n")[0].strip()
            correct = answer.casefold() == example["expected"].casefold()
            predictions.append({"input": example["input"], "expected": example["expected"],
                                "obtained": answer, "correct": correct})
    return {
        "accuracy": sum(row["correct"] for row in predictions) / len(predictions),
        "target_loss": sum(losses) / len(losses),
        "mean_request_ms": (time.perf_counter() - started) * 1000 / len(test),
        "cases": predictions,
    }


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    set_seed(SEED)
    torch.set_num_threads(4)
    source = ROOT / "data" / "ft" / "ocr_finetuning_local.jsonl"
    groups = defaultdict(list)
    for line in source.read_text(encoding="utf-8").splitlines():
        example = json.loads(line)
        groups[example["expected"]].append(example)
    train, test = [], []
    rng = random.Random(SEED)
    for name in sorted(groups):
        rows = groups[name]
        rng.shuffle(rows)
        test.extend(rows[:2])
        train.extend(rows[2:])
    if {row["input"] for row in train} & {row["input"] for row in test}:
        raise ValueError("Texto duplicado entre treino e teste")
    for name, rows in (("train", train), ("test", test)):
        path = ROOT / "data" / "ft" / f"llm_{name}.jsonl"
        path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")

    revision = MODEL_REVISION
    print(f"Modelo {MODEL_ID} revisão {revision}; treino {len(train)}; teste {len(test)}", flush=True)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, revision=revision)
    tokenizer.pad_token = tokenizer.eos_token
    model = AutoModelForCausalLM.from_pretrained(MODEL_ID, revision=revision, torch_dtype=torch.float32)
    print("Medindo modelo original...", flush=True)
    baseline = evaluate(model, tokenizer, test)
    write_json(OUTPUT / "base.json", baseline)
    config = LoraConfig(r=8, lora_alpha=16, target_modules=["q_proj", "v_proj"],
                        lora_dropout=0.05, bias="none", task_type="CAUSAL_LM")
    model = get_peft_model(model, config)
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    optimizer = torch.optim.AdamW((p for p in model.parameters() if p.requires_grad), lr=0.001)
    model.config.use_cache = False
    started = time.perf_counter()
    logs = []
    epochs = 3
    for epoch in range(epochs):
        model.train()
        rng.shuffle(train)
        for offset in range(0, len(train), 4):
            batch = batch_tensors(train[offset:offset + 4], tokenizer)
            optimizer.zero_grad(set_to_none=True)
            loss = model(**batch).loss
            if not torch.isfinite(loss):
                raise RuntimeError("Loss não finita")
            loss.backward()
            torch.nn.utils.clip_grad_norm_((p for p in model.parameters() if p.requires_grad), 1.0)
            optimizer.step()
            entry = {"epoch": epoch + 1, "step": len(logs) + 1, "loss": round(loss.item(), 6)}
            logs.append(entry)
            if entry["step"] % 10 == 0:
                print(json.dumps(entry), flush=True)
        write_json(OUTPUT / "training_log.json", logs)
    training_seconds = time.perf_counter() - started
    model.config.use_cache = True
    checkpoint = ROOT / "models" / "ocr-smollm2-lora"
    model.save_pretrained(checkpoint)
    tokenizer.save_pretrained(checkpoint)
    adapter_dir = OUTPUT / "adapter"
    adapter_dir.mkdir(exist_ok=True)
    for name in ("adapter_config.json", "adapter_model.safetensors"):
        shutil.copy2(checkpoint / name, adapter_dir / name)
    print("Medindo modelo ajustado...", flush=True)
    tuned = evaluate(model, tokenizer, test)
    write_json(OUTPUT / "ajustado.json", tuned)
    files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in checkpoint.iterdir() if p.is_file()}
    metrics = {
        "executed_at": datetime.now(timezone.utc).isoformat(),
        "model": MODEL_ID, "revision": revision, "seed": SEED,
        "train_examples": len(train), "test_examples": len(test), "classes": len(groups),
        "epochs": epochs, "batch_size": 4, "learning_rate": 0.001,
        "lora_r": 8, "lora_alpha": 16, "trainable_parameters": trainable,
        "training_seconds": training_seconds, "device": "cpu", "threads": 4,
        "baseline_accuracy": baseline["accuracy"], "tuned_accuracy": tuned["accuracy"],
        "baseline_target_loss": baseline["target_loss"], "tuned_target_loss": tuned["target_loss"],
        "baseline_mean_request_ms": baseline["mean_request_ms"],
        "tuned_mean_request_ms": tuned["mean_request_ms"],
        "api_cost_usd": 0, "electricity_cost_measured": False,
        "dataset_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "checkpoint_sha256": files,
        "limitations": "Dataset sintético pequeno. Teste contém as mesmas classes e família de templates do treino. Sem validação clínica ou medição do OCR de câmera.",
    }
    write_json(OUTPUT / "metricas.json", metrics)
    print(json.dumps(metrics, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
