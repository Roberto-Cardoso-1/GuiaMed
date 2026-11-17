"""RAG local e reproduzível para as bulas fictícias do GuiaMed.

Não usa API externa nem envia texto de saúde para terceiros. A recuperação usa
TF-IDF por seção e a geração é um template conservador que só reproduz conteúdo
presente nos chunks recuperados, sempre com citação.
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


PROMPT_VERSION = "v2-local-grounded"
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


class LocalRag:
    """Índice TF-IDF em memória sobre seções completas de bulas fictícias."""

    def __init__(self, data_path: Path = DEFAULT_DATA_PATH) -> None:
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

    def answer(self, query: str) -> RagResult:
        started = time.perf_counter()
        normalized_query = normalize(query)
        hostile = any(pattern in normalized_query for pattern in HOSTILE_PATTERNS)
        hits = self.search(query)
        best = hits[0] if hits else None
        if best is None or best.score < 0.08:
            response = (
                "Não encontrei informação suficiente nas bulas fictícias cadastradas. "
                "Confirme o nome do medicamento e tente novamente."
            )
            sources: tuple[str, ...] = ()
            medicine = None
            confidence = 0.0
        else:
            sources = tuple(hit.chunk.chunk_id for hit in hits)
            medicine = best.chunk.medicine
            response = (
                f"Segundo a bula fictícia de {medicine}, na seção {best.chunk.section.replace('_', ' ')}: "
                f"{best.chunk.content} Fonte: {best.chunk.chunk_id}."
            )
            confidence = round(best.score, 4)
        latency_ms = round((time.perf_counter() - started) * 1000, 3)
        return RagResult(
            response=response,
            sources=sources,
            medicine=medicine,
            confidence=confidence,
            prompt_version=PROMPT_VERSION,
            latency_ms=latency_ms,
            cost_usd=0.0,
            hostile_input_detected=hostile,
        )


@lru_cache(maxsize=1)
def get_local_rag() -> LocalRag:
    return LocalRag()
