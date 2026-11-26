"""Continua o treino LoRA e mede generalização em layouts sintéticos inéditos."""

import argparse
import hashlib
import json
import math
import random
import re
import time
from pathlib import Path

import torch
from peft import PeftModel, get_peft_model_state_dict, set_peft_model_state_dict
from transformers import AutoModelForCausalLM, AutoTokenizer, set_seed

from run_llm_finetuning import MODEL_ID, MODEL_REVISION, ROOT, batch_tensors, evaluate, write_json

OUTPUT = ROOT / "evidencias" / "ia" / "finetuning_v2"
PREVIOUS = ROOT / "evidencias" / "ia" / "finetuning" / "adapter"
SEED = 20261118
BATCH_SIZE = 8
EPOCHS = 3
LEARNING_RATE = 3e-4

TRAIN_LAYOUTS = (
    "Nome comercial {nome} | composição {ativo}",
    "{nome}\nApresentação: {forma}\nLote simulado {lote}",
    "EMBALAGEM DIGITALIZADA - referência {lote} - {nome} - {ativo}",
    "Ficha de treino\n{ativo}\nMarca: {nome}",
    "Catálogo de amostras\n{nome} | código {lote}",
)
VALIDATION_LAYOUT = "Amostra da farmácia {lote}\nProduto {nome}\nForma {forma}"
TEST_LAYOUTS = (
    "Etiq. de estoque n. {lote}\n{forma}\nMarca impressa {nome}",
    "{ativo}\nConferência de rótulo {lote}\n{nome} | embalagem ilustrativa",
)

CONFUSIONS = {"o": "0", "i": "1", "l": "I", "m": "rn", "e": "3", "a": "4", "s": "5", "t": "7"}


def corrupt_name(name, rng, variant):
    if variant % 6 == 0:
        index = rng.randrange(2, len(name) - 1)
        return name[:index] + " " + name[index:]
    if variant % 6 == 1:
        index = rng.randrange(len(name) - 1)
        return name[:index] + name[index + 1] + name[index] + name[index + 2:]
    if variant % 6 == 2:
        index = rng.randrange(len(name))
        return name[:index] + name[index + 1:]
    for _ in range(1 if variant % 6 == 3 else 2):
        positions = [index for index, letter in enumerate(name) if letter.lower() in CONFUSIONS]
        if not positions:
            break
        index = rng.choice(positions)
        name = name[:index] + CONFUSIONS[name[index].lower()] + name[index + 1:]
    return name


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def input_key(row):
    return re.sub(r"\s+", " ", row["input"]).strip().casefold()


def make_example(medicine, layout, rng, variant):
    label = medicine["nome_comercial"]
    name = corrupt_name(label, rng, variant)
    if rng.random() < 0.5:
        name = name.upper()
    return {
        "input": layout.format(nome=name, ativo=medicine["principio_ativo"],
                               forma=medicine["forma_farmaceutica"], lote=rng.randrange(100, 999)),
        "expected": label,
    }


def prepare_splits(output):
    medicines = json.loads((ROOT / "data" / "bulas.json").read_text(encoding="utf-8"))["bulas"]
    train_rng = random.Random(SEED)
    evaluation_rng = random.Random(SEED + 1)
    train = read_jsonl(ROOT / "data" / "ft" / "llm_train.jsonl")
    train.extend(
        make_example(medicine, TRAIN_LAYOUTS[index % len(TRAIN_LAYOUTS)], train_rng, index)
        for medicine in medicines for index in range(10)
    )
    validation = [make_example(medicine, VALIDATION_LAYOUT, evaluation_rng, i)
                  for i, medicine in enumerate(medicines)]
    test = [make_example(medicine, layout, evaluation_rng, i + 5 * j)
            for i, medicine in enumerate(medicines) for j, layout in enumerate(TEST_LAYOUTS)]
    old_test = read_jsonl(ROOT / "data" / "ft" / "llm_test.jsonl")
    old_test_keys = {input_key(row) for row in old_test}
    clean_train = [row for row in train if input_key(row) not in old_test_keys]
    removed_count = len(train) - len(clean_train)
    seen = set()
    for split in (clean_train, validation, test, old_test):
        keys = {input_key(row) for row in split}
        if seen & keys:
            raise ValueError("OCR repetido entre conjuntos")
        seen.update(keys)
    for filename, rows in (("treino", clean_train), ("validacao", validation), ("teste", test)):
        (output / f"{filename}.jsonl").write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8"
        )
    return clean_train, validation, test, old_test, removed_count


def paired_summary(previous, candidate):
    before, after = previous["cases"], candidate["cases"]
    assert [(r["input"], r["expected"]) for r in before] == [(r["input"], r["expected"]) for r in after]
    corrected = sum(not a["correct"] and b["correct"] for a, b in zip(before, after))
    regressed = sum(a["correct"] and not b["correct"] for a, b in zip(before, after))
    discordant = corrected + regressed
    paired_p = min(1.0, 2 * sum(math.comb(discordant, count) for count in range(min(corrected, regressed) + 1))
                   / 2 ** discordant)
    return {"examples": len(before), "v1_correct": sum(r["correct"] for r in before),
            "v2_correct": sum(r["correct"] for r in after), "corrected": corrected, "regressed": regressed,
            "gain_pp": 100 * (candidate["accuracy"] - previous["accuracy"]), "paired_exact_p": paired_p}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT, help="Pasta dos resultados e do adaptador")
    output = parser.parse_args().output_dir
    output.mkdir(parents=True, exist_ok=True)
    set_seed(SEED)
    torch.set_num_threads(4)
    train, validation, test, old_test, removed_count = prepare_splits(output)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, revision=MODEL_REVISION)
    tokenizer.pad_token = tokenizer.eos_token
    base = AutoModelForCausalLM.from_pretrained(MODEL_ID, revision=MODEL_REVISION, torch_dtype=torch.float32)
    model = PeftModel.from_pretrained(base, PREVIOUS, is_trainable=True)
    params = [parameter for parameter in model.parameters() if parameter.requires_grad]
    optimizer = torch.optim.AdamW(params, lr=LEARNING_RATE)
    rng = random.Random(SEED + 2)
    initial_weights = {key: value.detach().clone() for key, value in get_peft_model_state_dict(model).items()}
    initial_validation = evaluate(model, tokenizer, validation)
    validation_log = [{"epoch": 0, "accuracy": initial_validation["accuracy"],
                       "target_loss": initial_validation["target_loss"]}]
    print(f"Treino {len(train)}; validação {len(validation)}; teste inédito {len(test)}", flush=True)
    print(f"Validação V1: {initial_validation['accuracy']:.1%}", flush=True)
    best_weights = None
    best_epoch = None
    best_score = (-1.0, -math.inf)
    history = []
    train_seconds = 0.0
    for epoch in range(1, EPOCHS + 1):
        started = time.perf_counter()
        model.train()
        model.config.use_cache = False
        rng.shuffle(train)
        for offset in range(0, len(train), BATCH_SIZE):
            batch = batch_tensors(train[offset:offset + BATCH_SIZE], tokenizer)
            optimizer.zero_grad(set_to_none=True)
            loss = model(**batch).loss
            if not torch.isfinite(loss):
                raise ValueError("Perda de treino não finita")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(params, 1)
            optimizer.step()
            history.append({"epoch": epoch, "step": len(history) + 1, "loss": loss.item()})
            if len(history) % 20 == 0:
                print(f"Época {epoch}/{EPOCHS}, passo {len(history)}, perda {loss.item():.4f}", flush=True)
        train_seconds += time.perf_counter() - started
        model.config.use_cache = True
        score = evaluate(model, tokenizer, validation)
        row = {"epoch": epoch, "accuracy": score["accuracy"], "target_loss": score["target_loss"]}
        validation_log.append(row)
        print(f"Validação época {epoch}: {score['accuracy']:.1%}", flush=True)
        ranking = (score["accuracy"], -score["target_loss"])
        if ranking > best_score:
            best_score = ranking
            best_epoch = epoch
            best_weights = {key: value.detach().clone() for key, value in get_peft_model_state_dict(model).items()}
        write_json(output / "treino.json", {"steps": history, "validation": validation_log})

    # Os conjuntos de teste só são avaliados após selecionar a época.
    print(f"Época {best_epoch} selecionada. Avaliando V1 e V2 nos mesmos textos.", flush=True)
    set_peft_model_state_dict(model, initial_weights)
    previous = evaluate(model, tokenizer, test)
    previous_old = evaluate(model, tokenizer, old_test)
    set_peft_model_state_dict(model, best_weights)
    model.save_pretrained(output / "adapter")
    candidate = evaluate(model, tokenizer, test)
    candidate_old = evaluate(model, tokenizer, old_test)
    write_json(output / "respostas.json", {"novo_teste": {"v1": previous, "v2": candidate},
                                         "teste_anterior": {"v1": previous_old, "v2": candidate_old}})
    metrics = {
        "model": MODEL_ID, "revision": MODEL_REVISION, "seed": SEED,
        "train_examples": len(train), "validation_examples": len(validation),
        "removed_train_rows_overlapping_old_test_after_normalization": removed_count,
        "train_seconds": train_seconds, "batch_size": BATCH_SIZE, "learning_rate": LEARNING_RATE,
        "tested_epochs": EPOCHS, "selected_epoch": best_epoch,
        "updates": len(history), "trainable_parameters": sum(p.numel() for p in params),
        "new_test": paired_summary(previous, candidate),
        "old_test": paired_summary(previous_old, candidate_old),
        "previous_adapter_sha256": hashlib.sha256((PREVIOUS / "adapter_model.safetensors").read_bytes()).hexdigest(),
        "adapter_sha256": hashlib.sha256((output / "adapter" / "adapter_model.safetensors").read_bytes()).hexdigest(),
    }
    write_json(output / "metricas.json", metrics)
    print(json.dumps(metrics, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
