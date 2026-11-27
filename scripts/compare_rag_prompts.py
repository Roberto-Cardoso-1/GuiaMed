"""Compare actual local LLM generation with frozen retrieval and paired prompts."""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import asdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
from pathlib import Path
import platform
import re
import statistics
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from local_rag import LocalRag, normalize


def digest(value: object) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def numeric_literals(text: str) -> set[str]:
    # An indicator only: this does not establish semantic factuality or units.
    values = set()
    for value in re.findall(r"\d+(?:[.,]\d+)*", text):
        if re.fullmatch(r"\d{1,3}(?:\.\d{3})+(?:,\d+)?", value):
            value = value.replace(".", "")
        try:
            values.add(format(Decimal(value.replace(",", ".")).normalize(), "f"))
        except InvalidOperation:
            values.add(value)
    return values


def p95(values: list[float]) -> float | None:
    return sorted(values)[min(len(values) - 1, math.ceil(.95 * len(values)) - 1)] if values else None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--timeout-ms", type=int, default=10000)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--power-watts", type=float)
    parser.add_argument("--brl-per-kwh", type=float)
    args = parser.parse_args()
    if not 1 <= args.repeats <= 10 or not 1 <= args.timeout_ms <= 60000:
        parser.error("Use 1..10 repeats and 1..60000 timeout-ms")
    if (args.power_watts is None) != (args.brl_per_kwh is None):
        parser.error("Supply both energy assumptions, or neither")
    if args.power_watts is not None and (args.power_watts < 0 or args.brl_per_kwh < 0):
        parser.error("Energy assumptions must be nonnegative")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    dataset = ROOT / "data/qa/rag_prompt_comparison.json"
    source_paths = [ROOT / "backend/local_rag.py", ROOT / "backend/rag_llm.py",
                    ROOT / "prompts/v1.md", ROOT / "prompts/v2.md", dataset]
    input_hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in source_paths}
    cases = json.loads(dataset.read_text(encoding="utf-8"))["cases"]
    started = time.perf_counter()
    rag = LocalRag()
    index_ms = (time.perf_counter() - started) * 1000
    contexts, search_ms = {}, {}
    for case in cases:
        started = time.perf_counter()
        contexts[case["id"]] = rag.search(case["question"])
        search_ms[case["id"]] = (time.perf_counter() - started) * 1000
    snapshots = {key: [asdict(hit) for hit in hits] for key, hits in contexts.items()}
    (output / "contexts.json").write_text(json.dumps(snapshots, ensure_ascii=False, indent=2), encoding="utf-8")

    # Explicit warmup/cold load, retained but excluded from paired timing stats.
    first = cases[0]
    warmup = rag.answer(first["question"], prompt_version="v2", hits=contexts[first["id"]],
                        timeout_ms=args.timeout_ms).evaluation_record()
    rows = []
    for repeat in range(args.repeats):
        for index, case in enumerate(cases):
            versions = ("v1", "v2") if (repeat + index) % 2 == 0 else ("v2", "v1")
            hits = contexts[case["id"]]
            frozen_hash = digest(snapshots[case["id"]])
            allowed = {hit.chunk.chunk_id for hit in hits}
            for version in versions:
                wall, cpu = time.perf_counter(), time.process_time()
                result = rag.answer(case["question"], prompt_version=version, hits=hits,
                                    timeout_ms=args.timeout_ms)
                wall_ms, cpu_ms = (time.perf_counter() - wall) * 1000, (time.process_time() - cpu) * 1000
                record = result.evaluation_record()
                assert set(record["sources"]).issubset(allowed), record
                assert frozen_hash == digest([asdict(hit) for hit in hits]), "Context changed"
                raw = record.get("raw_response") or ""
                normalized = normalize(raw)
                citations = set(re.findall(r"[a-z0-9-]+#[a-z_]+", raw.lower()))
                source_numbers = numeric_literals(" ".join(hit.chunk.content for hit in hits))
                estimate = None if args.power_watts is None else (
                    args.power_watts * (wall_ms + search_ms[case["id"]]) / 3_600_000_000 * args.brl_per_kwh
                )
                row = {
                    "case_id": case["id"], "question": case["question"], "variant": version,
                    "repeat": repeat + 1, "context_sha256": frozen_hash,
                    "retrieval_once_ms": search_ms[case["id"]], "answer_wall_ms": wall_ms,
                    "process_cpu_ms": cpu_ms, "rag_wall_with_frozen_retrieval_ms": wall_ms + search_ms[case["id"]],
                    "energy_cost_estimate_brl": estimate,
                    "raw_indicators": {
                        "expected_term_present": any(normalize(term) in normalized for term in case["answer_terms_any"]),
                        "expected_source_cited": bool(citations & set(case["expected_sources"])),
                        "all_cited_sources_retrieved": citations.issubset(allowed),
                        "unseen_numeric_literals": sorted(numeric_literals(raw) - source_numbers),
                        "required_numbers_present": set(case.get("required_numbers", [])).issubset(numeric_literals(raw)),
                    },
                    "result": record,
                }
                rows.append(row)
                print(json.dumps({"case": case["id"], "prompt": version, "repeat": repeat + 1,
                                  "mode": record["mode"], "llm_called": record["llm_called"]}), flush=True)

    summaries = {}
    for version in ("v1", "v2"):
        selected = [row for row in rows if row["variant"] == version]
        generated = [row for row in selected if row["result"]["llm_called"]]
        generation_ms = [row["result"]["geracao_ms"] for row in generated]
        summaries[version] = {
            "requests": len(selected), "llm_calls": len(generated),
            "modes": dict(Counter(row["result"]["mode"] for row in selected)),
            "generation_mean_ms": statistics.fmean(generation_ms) if generation_ms else None,
            "generation_median_ms": statistics.median(generation_ms) if generation_ms else None,
            "generation_p95_ms": p95(generation_ms),
            "rag_mean_ms": statistics.fmean(row["rag_wall_with_frozen_retrieval_ms"] for row in selected),
            "input_tokens": sum(row["result"].get("input_tokens") or 0 for row in generated),
            "output_tokens": sum(row["result"].get("output_tokens") or 0 for row in generated),
            "external_api_cost_usd": sum(row["result"]["cost_usd"] for row in selected),
            "energy_cost_estimate_brl": sum(row["energy_cost_estimate_brl"] for row in selected) if args.power_watts is not None else None,
        }
    assert input_hashes == {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                            for path in source_paths}, "Code, prompts or questions changed during comparison"
    acceptance = all(summaries[v]["llm_calls"] > 0 for v in summaries) and any(
        row["variant"] == "v2" and row["result"]["mode"] == "llm_grounded" for row in rows
    )
    report = {
        "utc": datetime.now(timezone.utc).isoformat(), "python": sys.version, "platform": platform.platform(),
        "dataset_sha256": hashlib.sha256(dataset.read_bytes()).hexdigest(), "repeats": args.repeats,
        "source_and_input_sha256": input_hashes,
        "prompt_file_sha256": {v: hashlib.sha256((ROOT / f"prompts/{v}.md").read_bytes()).hexdigest() for v in summaries},
        "context_sha256": {key: digest(value) for key, value in snapshots.items()},
        "index_ms": index_ms, "warmup_excluded": warmup, "summary": summaries, "rows": rows,
        "runtime_demonstration_passed": acceptance,
        "cost": {"external_api_billing_only_in_cost_usd": True, "energy_measured": False,
                 "hardware_cost_measured": False, "power_watts_assumed": args.power_watts,
                 "brl_per_kwh_assumed": args.brl_per_kwh,
                 "formula": "watts * wall_seconds / 3600000 * BRL_per_kWh"},
        "limits": ["Five fictitious test questions; no clinical validation.",
                   "Numeric/citation/term indicators are not a semantic factuality assessment.",
                   "RAG timing excludes audit, transport and n8n; HTTP/workflow tested separately.",
                   "Frozen retrieval is measured once; provided-hit validation remains in answer timing.",
                   "Pre-model refusals do not demonstrate a prompt effect.",
                   "Money for electricity is a scenario estimate, not an energy measurement."],
    }
    (output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# Comparação pareada de prompts RAG", "", "Perguntas e contextos idênticos; geração greedy, ordem v1/v2 alternada.", "",
             "| Prompt | Chamadas LLM | Modos | Mediana geração ms | p95 ms | Tokens saída | Cobrança externa USD |",
             "|---|---:|---|---:|---:|---:|---:|"]
    for version, summary in summaries.items():
        lines.append(f"| {version} | {summary['llm_calls']} | {summary['modes']} | {summary['generation_median_ms']} | {summary['generation_p95_ms']} | {summary['output_tokens']} | {summary['external_api_cost_usd']} |")
    lines += ["", "A cobrança externa não inclui energia/hardware. Hipóteses e estimativa energética estão em results.json.",
              "Os indicadores automáticos não comprovam factualidade. Respostas brutas rejeitadas permanecem visíveis."]
    for case in cases:
        lines += ["", f"## {case['id']}: {case['question']}"]
        for version in ("v1", "v2"):
            row = next(row for row in rows if row["case_id"] == case["id"] and row["variant"] == version)
            lines += ["", f"### {version} — {row['result']['mode']}", "", "Saída bruta do modelo:", "",
                      "```text", row["result"].get("raw_response") or "(modelo não gerou saída)", "```",
                      "", "Resposta entregue:", "", "```text", row["result"]["response"], "```"]
    (output / "comparison.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"runtime_demonstration_passed": acceptance, "output": str(output)}), flush=True)
    if not acceptance:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
