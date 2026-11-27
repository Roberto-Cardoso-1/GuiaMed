"""RAG local e reproduzível para as bulas fictícias do GuiaMed.

Não usa API externa nem envia texto de saúde para terceiros. A recuperação usa
TF-IDF por seção e um LLM base local gera a partir dos chunks recuperados.
Saídas não verificáveis usam fallback explicitamente identificado.
"""

from __future__ import annotations

import json
import math
import re
import time
import unicodedata
from collections import Counter
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from rag_llm import (
    ContextTooLong, GenerationFailed, GenerationTimeout, GenerationUnavailable, MODEL_ID, MODEL_REVISION,
    get_rag_llm, literal_passages, load_prompt, prompt_messages,
)


PROMPT_VERSION = "v2"
DEFAULT_DATA_PATH = Path(__file__).resolve().parents[1] / "data" / "bulas.json"
SECTIONS = (
    "indicacao",
    "posologia",
    "contraindicacoes",
    "efeitos_adversos",
    "interacoes",
    "advertencias",
)
STOPWORDS = {
    "a",
    "ao",
    "aos",
    "as",
    "com",
    "como",
    "da",
    "das",
    "de",
    "do",
    "dos",
    "e",
    "em",
    "esse",
    "esta",
    "este",
    "o",
    "os",
    "para",
    "por",
    "qual",
    "que",
    "um",
    "uma",
}
HOSTILE_PATTERNS = (
    "ignore as instrucoes",
    "ignore instrucoes",
    "mostre o prompt",
    "revele o prompt",
    "system prompt",
    "drop table",
    "delete from",
)
SECTION_INTENTS = {
    "indicacao": {"serve", "indicado", "indicacao", "trata", "tratamento"},
    "posologia": {"dose", "dosagem", "tomar", "usar", "posologia"},
    "contraindicacoes": {"contraindicacao", "contraindicado", "nao", "pode"},
    "efeitos_adversos": {"efeito", "efeitos", "adverso", "reacao", "reacoes"},
    "interacoes": {"interacao", "interacoes", "misturar", "junto"},
    "advertencias": {"advertencia", "cuidado", "cuidados", "alerta"},
}


def normalize(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value.lower())
    without_accents = "".join(char for char in decomposed if not unicodedata.combining(char))
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]+", " ", without_accents)).strip()


def tokenize(value: str) -> list[str]:
    return [token for token in normalize(value).split() if len(token) >= 3 and token not in STOPWORDS]


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    medicine: str
    active_ingredient: str
    section: str
    content: str


@dataclass(frozen=True)
class SearchHit:
    chunk: Chunk
    score: float


@dataclass(frozen=True)
class RagResult:
    response: str
    sources: tuple[str, ...]
    medicine: str | None
    confidence: float
    prompt_version: str
    latency_ms: float
    cost_usd: float
    hostile_input_detected: bool
    busca_ms: float
    geracao_ms: float
    total_ms: float
    input_tokens: int | None
    output_tokens: int | None
    model: str
    revision: str
    mode: str
    raw_response: str | None
    fallback_reason: str | None
    prompt_sha256: str
    load_ms: float
    generation_attempted: bool
    llm_called: bool
    generator_backend: str
    citation_origin: str
    decoding: str

    def evaluation_record(self) -> dict:
        """Explicit opt-in evidence; never logged or persisted automatically.

        raw_response may echo sensitive input. Only persist synthetic probes or
        appropriately authorized, sanitized evaluations.
        """
        from dataclasses import asdict
        return asdict(self)


class LocalRag:
    """Índice TF-IDF em memória sobre seções completas de bulas fictícias."""

    def __init__(self, data_path: Path = DEFAULT_DATA_PATH, *, generator=None) -> None:
        self._generator = generator
        payload = json.loads(data_path.read_text(encoding="utf-8"))
        medicines = payload.get("bulas", [])
        chunks: list[Chunk] = []
        self._medicine_terms: dict[str, tuple[str, str]] = {}
        for medicine in medicines:
            name = str(medicine["nome_comercial"])
            active = str(medicine["principio_ativo"])
            self._medicine_terms[name] = (normalize(name), normalize(active))
            for section in SECTIONS:
                content = str(medicine.get(section, "")).strip()
                if content:
                    chunks.append(
                        Chunk(
                            chunk_id=f"{normalize(name).replace(' ', '-') }#{section}",
                            medicine=name,
                            active_ingredient=active,
                            section=section,
                            content=content,
                        )
                    )

        self.chunks = tuple(chunks)
        documents = [tokenize(f"{chunk.medicine} {chunk.active_ingredient} {chunk.section} {chunk.content}") for chunk in chunks]
        document_frequency: Counter[str] = Counter()
        for tokens in documents:
            document_frequency.update(set(tokens))
        count = max(1, len(documents))
        self._idf = {
            token: math.log((1 + count) / (1 + frequency)) + 1.0
            for token, frequency in document_frequency.items()
        }
        self._vectors = tuple(self._vector(tokens) for tokens in documents)

    def _vector(self, tokens: list[str]) -> dict[str, float]:
        counts = Counter(tokens)
        if not counts:
            return {}
        maximum = max(counts.values())
        return {
            token: (0.5 + 0.5 * frequency / maximum) * self._idf.get(token, 1.0)
            for token, frequency in counts.items()
        }

    @staticmethod
    def _cosine(left: dict[str, float], right: dict[str, float]) -> float:
        if not left or not right:
            return 0.0
        common = left.keys() & right.keys()
        numerator = sum(left[token] * right[token] for token in common)
        left_norm = math.sqrt(sum(value * value for value in left.values()))
        right_norm = math.sqrt(sum(value * value for value in right.values()))
        return numerator / (left_norm * right_norm) if left_norm and right_norm else 0.0

    def identify_medicine(self, query: str) -> str | None:
        normalized_query = normalize(query)
        exact = [
            name
            for name, (normalized_name, normalized_active) in self._medicine_terms.items()
            if normalized_name in normalized_query or normalized_active in normalized_query
        ]
        if exact:
            return max(exact, key=len)
        return None

    def search(self, query: str, top_k: int = 3) -> tuple[SearchHit, ...]:
        query_tokens = tokenize(query)
        query_vector = self._vector(query_tokens)
        query_token_set = set(query_tokens)
        identified = self.identify_medicine(query)
        scored: list[SearchHit] = []
        for chunk, vector in zip(self.chunks, self._vectors):
            score = self._cosine(query_vector, vector)
            if identified and chunk.medicine == identified:
                score += 0.35
            if query_token_set & SECTION_INTENTS.get(chunk.section, set()):
                score += 0.3
            if score > 0:
                scored.append(SearchHit(chunk=chunk, score=min(score, 1.0)))
        scored.sort(key=lambda hit: (-hit.score, hit.chunk.chunk_id))
        return tuple(scored[:top_k])

    def answer(
        self, query: str, prompt_version: str = "v2", *,
        hits: tuple[SearchHit, ...] | None = None, timeout_ms: int = 15000,
    ) -> RagResult:
        started = time.perf_counter()
        prompt = load_prompt(prompt_version)
        normalized_query = normalize(query)
        hostile = any(pattern in normalized_query for pattern in HOSTILE_PATTERNS)
        search_started = time.perf_counter()
        if hits is None:
            hits = self.search(query)
        else:
            # Comparisons can freeze identical hits, but cannot inject invented
            # sources or alter indexed content under an existing source ID.
            indexed = {chunk.chunk_id: chunk for chunk in self.chunks}
            if any(indexed.get(hit.chunk.chunk_id) != hit.chunk or not 0 <= hit.score <= 1 for hit in hits):
                raise ValueError("hits must contain unmodified indexed chunks and valid scores")
        busca_ms = (time.perf_counter() - search_started) * 1000
        best = hits[0] if hits else None
        raw_response = None
        input_tokens = output_tokens = None
        geracao_ms = load_ms = 0.0
        reason = None
        generation_attempted = llm_called = False
        backend = "not_called"
        citation_origin = "none"
        decoding = "not_called"
        mode = "refusal_no_evidence"
        model, revision = MODEL_ID, MODEL_REVISION
        if hostile:
            response = "Consulta recusada: contém instruções não confiáveis. Refaça apenas a pergunta sobre a bula fictícia."
            sources: tuple[str, ...] = ()
            medicine = self.identify_medicine(query)
            confidence = 0.0
            mode = "refusal_hostile"
        elif best is None or best.score < 0.08 or self.identify_medicine(query) is None:
            response = (
                "Não encontrei informação suficiente nas bulas fictícias cadastradas. "
                "Confirme o nome do medicamento e tente novamente."
            )
            sources = ()
            medicine = None
            confidence = 0.0
        else:
            sources = (best.chunk.chunk_id,)
            medicine = best.chunk.medicine
            response = (
                f"Segundo a bula fictícia de {medicine}, na seção {best.chunk.section.replace('_', ' ')}: "
                f"{best.chunk.content} Fonte: {best.chunk.chunk_id}."
            )
            confidence = round(best.score, 4)
            generation_started = time.perf_counter()
            try:
                generator = self._generator if self._generator is not None else get_rag_llm()
                generation_attempted = True
                backend = "transformers" if self._generator is None else "fake"
                generated = generator.generate(prompt_messages(prompt, query, [
                    {"fonte": hit.chunk.chunk_id, "texto": hit.chunk.content} for hit in hits
                ]), timeout_ms=timeout_ms)
                raw_response = generated.raw_response
                backend, llm_called = generated.backend, generated.llm_called
                decoding = "extractive_trie" if generated.constrained else "unconstrained"
                input_tokens, output_tokens = generated.input_tokens, generated.output_tokens
                load_ms = generated.load_ms
                model, revision = generated.model, generated.revision
                if generated.timed_out:
                    reason = "timeout"
                else:
                    # Exact extractive verification keeps dose/negation intact.
                    # A free paraphrase from this tiny model is not a safety proof.
                    canonical = lambda text: re.sub(r"\s+", " ", text).strip()
                    for hit in hits:
                        content, source = hit.chunk.content, hit.chunk.chunk_id
                        passages = (content,) if source.endswith("#posologia") else literal_passages(content)
                        permitted = tuple(text for passage in passages for text in (
                            passage, f"{passage} Fonte: {source}.", f"{passage} [{source}]",
                        ))
                        if canonical(raw_response) in tuple(map(canonical, permitted)):
                            response = raw_response.strip()
                            citation_origin = "structured_metadata" if canonical(raw_response) in tuple(map(canonical, passages)) else "model"
                            sources = (source,)
                            medicine = hit.chunk.medicine
                            mode = "llm_grounded"
                            break
                    else:
                        reason = "unverified_generation"
            except GenerationTimeout:
                reason = "timeout"
            except GenerationUnavailable:
                reason = "unavailable"
            except ContextTooLong:
                reason = "context_limit"
            except GenerationFailed:
                reason = "generation_error"
                llm_called = True
            except Exception:
                # Do not log library exceptions, prompts or generated health text.
                reason = "generation_error"
            finally:
                geracao_ms = (time.perf_counter() - generation_started) * 1000
            if reason:
                mode = "fallback_template"
                citation_origin = "template"
                response = f"Fallback extrativo; geração local não utilizada ({reason}). {response}"
        latency_ms = round((time.perf_counter() - started) * 1000, 3)
        return RagResult(
            response=response,
            sources=sources,
            medicine=medicine,
            confidence=confidence,
            prompt_version=prompt.version,
            latency_ms=latency_ms,
            cost_usd=0.0,
            hostile_input_detected=hostile,
            busca_ms=busca_ms, geracao_ms=geracao_ms, total_ms=latency_ms,
            input_tokens=input_tokens, output_tokens=output_tokens, model=model, revision=revision,
            mode=mode, raw_response=raw_response, fallback_reason=reason,
            prompt_sha256=prompt.sha256, load_ms=load_ms,
            generation_attempted=generation_attempted, llm_called=llm_called,
            generator_backend=backend, citation_origin=citation_origin,
            decoding=decoding,
        )


@lru_cache(maxsize=1)
def get_local_rag() -> LocalRag:
    return LocalRag()
