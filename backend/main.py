"""API V1 do GuiaMed.

O parser e deliberadamente conservador: ele nunca inventa dosagem ou validade
quando esses dados nao aparecem no OCR. O PostgreSQL e usado quando
``DATABASE_URL`` esta configurada; em desenvolvimento, a API continua utilizavel
com alergias mantidas em memoria durante a execucao.
"""

from __future__ import annotations

import asyncio
import calendar
import hashlib
import hmac
import os
import json
import logging
import re
import threading
import unicodedata
import uuid
import time
from dataclasses import dataclass, field
from contextlib import asynccontextmanager
from datetime import date, datetime
from difflib import SequenceMatcher
from functools import lru_cache
from pathlib import Path
from typing import Callable, Literal, Optional

import asyncpg
from asyncpg.pool import Pool
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from local_lora import MAX_OCR_CHARS, LocalLora, GENERATION_DEADLINE, DEFAULT_REAL_ADAPTER


def get_local_rag():
    # Optional generation dependencies must not prevent OCR/API imports or startup.
    from local_rag import get_local_rag as load_local_rag
    return load_local_rag()


class Settings(BaseSettings):
    database_url: Optional[str] = None
    api_host: str = "0.0.0.0"
    api_port: int = 8000
    log_level: str = "INFO"
    cors_origins: list[str] = Field(default_factory=list)
    tz: str = "America/Sao_Paulo"
    lora_enabled: bool = True
    lora_gguf_path: Path | None = None
    lora_adapter_dir: Path | None = DEFAULT_REAL_ADAPTER
    lora_threads: int = Field(default=4, ge=1, le=32)
    lora_max_calls: int = Field(default=4, ge=1, le=12)
    lora_budget_ms: int = Field(default=1500, ge=1, le=10000)

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")


settings = Settings()
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("guiamed")

db_pool: Optional[Pool] = None
memory_allergies: dict[str, set[str]] = {}
memory_audit: list[dict[str, object]] = []
memory_lock = threading.RLock()


MEDICINES = (
    {
        "nome": "Dipirona Sódica",
        "aliases": ("DIPIRONA", "DIPIRONA SODICA", "NOVALGINA", "METAMIZOL"),
        "componentes": ("dipirona", "metamizol"),
    },
    {
        "nome": "Nimesulida",
        "aliases": ("NIMESULIDA", "NISULID", "SCFLAM"),
        "componentes": ("nimesulida",),
    },
    {
        "nome": "Ibuprofeno",
        "aliases": ("IBUPROFENO", "ALIVIUM", "ADVIL"),
        "componentes": ("ibuprofeno",),
    },
    {
        "nome": "Paracetamol",
        "aliases": ("PARACETAMOL", "TYLENOL"),
        "componentes": ("paracetamol", "acetaminofeno"),
    },
    {
        "nome": "Ácido acetilsalicílico",
        "aliases": ("ACIDO ACETILSALICILICO", "ASPIRINA", "AAS"),
        "componentes": ("ácido acetilsalicílico", "aspirina"),
    },
    {
        "nome": "Amoxicilina",
        "aliases": ("AMOXICILINA", "AMOXIL"),
        "componentes": ("amoxicilina", "penicilina"),
    },
    {
        "nome": "Omeprazol",
        "aliases": ("OMEPRAZOL", "LOSEC"),
        "componentes": ("omeprazol",),
    },
    {
        "nome": "Rosuvastatina Cálcica",
        "aliases": (
            "ROSUVASTATINA",
            "ROSUVASTATINA CALCICA",
            "CRESTOR",
            "ROSUCORE",
            "ROSUCOR",
        ),
        "componentes": ("rosuvastatina", "rosuvastatina cálcica"),
    },
)

DCB_CATALOG_PATH = Path(__file__).parent / "data" / "dcb_medicamentos.json"
OCR_NON_MEDICINE_WORDS = {
    "CAIXA",
    "CAPSULA",
    "CAPSULAS",
    "COMPRIMIDO",
    "COMPRIMIDOS",
    "GENERICO",
    "MEDICAMENTO",
    "ORAL",
    "REVESTIDO",
    "REVESTIDOS",
    "USO",
}


class VerificarRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=200)
    texto_ocr: str = Field(..., min_length=1, max_length=20_000)
    timestamp: int


class VerificarResponse(BaseModel):
    nome: str
    miligramas: Optional[str] = None
    validade: Optional[str] = None
    vencido: bool = False
    alerta_alergia: bool = False
    componentes: list[str] = Field(default_factory=list)
    posologia: Optional[str] = None
    mensagem: Optional[str] = None
    perigo: bool = False


class AlergiaRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=200)
    nome_alergia: str = Field(..., min_length=2, max_length=200)
    timestamp: int


class AlergiaResponse(BaseModel):
    sucesso: bool
    mensagem: str
    alergias_registradas: list[str] = Field(default_factory=list)


class ReceitaRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=200)
    texto_ocr: str = Field(..., min_length=1, max_length=40_000)
    timestamp: int


class MedicamentoReceita(BaseModel):
    nome: str
    dosagem: str
    frequencia: str
    duracao: Optional[str] = None
    instrucoes: Optional[str] = None


class ReceitaResponse(BaseModel):
    medicamentos: list[MedicamentoReceita] = Field(default_factory=list)
    posologia_completa: str
    horarios: list[str] = Field(default_factory=list)
    mensagem: Optional[str] = None


class HealthResponse(BaseModel):
    status: str
    database: str
    timestamp: datetime


class RagRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    device_id: str = Field(..., min_length=1, max_length=200)
    pergunta: str = Field(..., min_length=3, max_length=4_000)
    timestamp: int
    prompt_version: Literal["v1", "v2"] = "v2"


class RagAuditReceipt(BaseModel):
    id: str
    backend: Literal["postgresql", "memoria"]
    persistida: bool


class RagResponse(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    resposta: str
    fontes: list[str] = Field(default_factory=list)
    medicamento: Optional[str] = None
    confianca: float = Field(..., ge=0.0, le=1.0)
    prompt_version: str
    latencia_ms: float = Field(..., ge=0.0)
    custo_usd: float = Field(..., ge=0.0)
    auditoria_id: str
    entrada_hostil_detectada: bool = False
    auditoria: RagAuditReceipt
    busca_ms: float = Field(..., ge=0.0)
    geracao_ms: float = Field(..., ge=0.0)
    load_ms: float = Field(..., ge=0.0)
    total_ms: float = Field(..., ge=0.0)
    input_tokens: int | None = Field(default=None, ge=0)
    output_tokens: int | None = Field(default=None, ge=0)
    model: str
    revision: str
    mode: Literal["llm_grounded", "fallback_template", "refusal_no_evidence", "refusal_hostile"]
    fallback: bool
    fallback_reason: str | None = None
    prompt_sha256: str
    generation_attempted: bool
    llm_called: bool
    generator_backend: str
    citation_origin: str
    decoding: str
    geracao_llm_real: bool
    consulta_sha256: str
    custos: dict[str, object]


def strip_accents(value: str) -> str:
    return "".join(
        char
        for char in unicodedata.normalize("NFKD", value)
        if not unicodedata.combining(char)
    )


def normalize_ocr(value: str) -> str:
    """Normaliza acentos e confusoes frequentes do OCR sem alterar o original."""
    text = strip_accents(value).upper()

    def fix_compact_package_date(match: re.Match[str]) -> str:
        label = match.group(1)
        month = match.group(2).replace("O", "0").replace("I", "1").replace("L", "1")
        year = match.group(3).replace("O", "0").replace("I", "1").replace("L", "1")
        return f"{label} {month}/{year}"

    # Fundos de caixa frequentemente juntam a letra ao mês: F02 26
    # (fabricação) e V02 28 (validade). Separa e corrige O/0 e I/1 antes
    # da normalização geral de palavras.
    text = re.sub(
        r"(?<![A-Z0-9])([VF])\s*([0-9OIL]{2})\s*[/\-. ]\s*([0-9OIL]{2,4})(?![A-Z0-9])",
        fix_compact_package_date,
        text,
    )
    text = re.sub(
        r"(?<![A-Z0-9])([VF])([0-9OIL]{2})([0-9OIL]{2})(?![A-Z0-9])",
        fix_compact_package_date,
        text,
    )

    def fix_digits(raw: str) -> str:
        return raw.replace("O", "0").replace("I", "1").replace("L", "1")

    def canonical_unit(unit: str) -> str:
        return "MG" if unit in {"RNG", "M9", "MQ"} else unit

    def fix_measure(match: re.Match[str]) -> str:
        return f"{fix_digits(match.group(1))} {canonical_unit(match.group(2))}"

    text = re.sub(
        r"\b([0-9OIL]{1,3})\.([O0]{3})\s*(MCG|MG|ML|G|UI|RNG|M9|MQ)\b",
        lambda match: f"{fix_digits(match.group(1))}000 {canonical_unit(match.group(3))}",
        text,
    )
    text = re.sub(
        r"\b([1-9])\s+([0O]{2,3})\s*(MCG|MG|ML|G|UI|RNG|M9|MQ)\b",
        lambda match: f"{match.group(1)}{fix_digits(match.group(2))} {canonical_unit(match.group(3))}",
        text,
    )
    text = re.sub(r"\b([0-9OIL]+(?:[.,][0-9OIL]+)?)\s*(MCG|MG|ML|G|UI|RNG|M9|MQ)\b", fix_measure, text)
    text = re.sub(
        r"\b([0-9OIL]{1,2})\s*[/\-.]\s*([0-9OIL]{2,4})\b",
        lambda match: f"{match.group(1).replace('O', '0').replace('I', '1').replace('L', '1')}/"
        f"{match.group(2).replace('O', '0').replace('I', '1').replace('L', '1')}",
        text,
    )
    tokens = []
    for token in text.split():
        if "0" in token and any(char.isalpha() for char in token):
            token = token.replace("0", "O")
        tokens.append(token)
    return re.sub(r"\s+", " ", " ".join(tokens)).strip()


@lru_cache(maxsize=1)
def load_dcb_catalog() -> tuple[dict[str, dict], tuple[tuple[str, dict], ...]]:
    """Carrega a base oficial DCB e cria índices compactos para o OCR."""
    try:
        raw_catalog = json.loads(DCB_CATALOG_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("Catálogo DCB indisponível: %s", exc)
        return {}, ()

    exact_index: dict[str, dict] = {}
    single_word_index: list[tuple[str, dict]] = []
    for item in raw_catalog:
        name = str(item.get("nome", "")).strip()
        normalized_name = normalize_ocr(name)
        if len(normalized_name) < 4:
            continue
        medicine = {
            "nome": name,
            "aliases": (name,),
            "componentes": (name,),
        }
        exact_index[normalized_name] = medicine
        if " " not in normalized_name and len(normalized_name) >= 7:
            single_word_index.append((normalized_name, medicine))

    logger.info("Catálogo DCB carregado com %s denominações", len(exact_index))
    return exact_index, tuple(single_word_index)


def _similarity(left: str, right: str) -> float:
    left_normalized = normalize_ocr(left)
    right_normalized = normalize_ocr(right)
    if left_normalized in right_normalized or right_normalized in left_normalized:
        return 1.0
    return SequenceMatcher(None, left_normalized, right_normalized).ratio()


def hostile_ocr(text: str) -> bool:
    return bool(re.search(r"\b(?:IGNORE (?:AS )?INSTRUCOES|RESPONDA (?:APENAS |COM )?|REVELE O PROMPT|SYSTEM PROMPT)\b", normalize_ocr(text)))


def exact_catalog_identities(normalized_text: str) -> dict[str, dict]:
    words = re.findall(r"[A-Z0-9]+", normalized_text)
    names = lora_catalog()[0]
    spans = []
    for size in range(1, min(6, len(words)) + 1):
        for start in range(len(words) - size + 1):
            if medicine := names.get(" ".join(words[start:start + size])):
                spans.append((start, start + size, medicine))
    # Prefer the longest overlapping name (salt/full DCB), but refuse distinct
    # non-overlapping identities, including curated + DCB on the same box.
    matches = {}
    furthest_end = -1
    for start, end, medicine in sorted(spans, key=lambda span: (span[0], -span[1])):
        if end > furthest_end:
            matches[medicine["nome"]] = medicine
            furthest_end = end
    return matches


def find_medicine(normalized_text: str) -> Optional[dict]:
    if hostile_ocr(normalized_text):
        return None
    matches = exact_catalog_identities(normalized_text)
    if matches:
        if len(matches) != 1:
            return None
        selected = next(iter(matches.values()))
        identity = normalize_ocr(selected["nome"])
        for other in evidence_candidates(normalized_text).values():
            other_identity = normalize_ocr(other["nome"])
            if other_identity not in identity and identity not in other_identity:
                return None
        return selected
    matches = evidence_candidates("\n".join(re.findall(r"[A-Z0-9]+", normalized_text)))
    return next(iter(matches.values())) if len(matches) == 1 else None


@lru_cache(maxsize=1)
def lora_catalog() -> tuple[dict[str, dict], dict[str, dict[str, dict]]]:
    dcb_names, _ = load_dcb_catalog()
    names = dict(dcb_names)
    for medicine in MEDICINES:
        for name in (medicine["nome"], *medicine["aliases"]):
            names[normalize_ocr(name)] = medicine

    compact_names: dict[str, dict[str, dict]] = {}
    for name, medicine in names.items():
        compact = name.replace(" ", "")
        if compact.isalpha() and len(compact) >= 7:
            compact_names.setdefault(compact, {})[medicine["nome"]] = medicine
    return names, compact_names


@lru_cache(maxsize=1)
def recovery_index() -> dict[str, set[str]]:
    # One edit retrieval: deletion keys bound work independently of catalog size.
    index: dict[str, set[str]] = {}
    for name in lora_catalog()[1]:
        for key in {name, *(name[:i] + name[i + 1:] for i in range(len(name)))}:
            index.setdefault(key, set()).add(name)
    return index


def evidence_candidates(text: str, budget: RecoveryBudget | None = None, observed: list[str] | None = None) -> dict[str, dict]:
    """Require a full OCR span, >=.90 similarity and .04 separation per span.

    Joining spaces is allowed; arbitrary substrings and model assertions are not
    evidence. Retrieval uses one-deletion neighborhoods (including transpositions).
    """
    if hostile_ocr(text):
        return {}
    compact_names = lora_catalog()[1]
    index = recovery_index()
    matches: dict[str, dict] = {}
    for line in text.splitlines():
        words = re.findall(r"[A-Z0-9]+", normalize_ocr(line))
        for start in range(len(words)):
            if budget is not None and not budget.available():
                raise TimeoutError("recovery preparation budget exhausted")
            for end in range(start + 1, min(start + 6, len(words)) + 1):
                span = "".join(words[start:end])
                if not span.isalpha() or len(span) < 7 or len(span) > 80:
                    continue
                exact = compact_names.get(span)
                if exact:
                    matches.update(exact)
                    if observed is not None:
                        observed.append(" ".join(words[start:end]))
                    continue
                keys = {span, *(span[:i] + span[i + 1:] for i in range(len(span)))}
                candidates = set().union(*(index.get(key, set()) for key in keys))
                scores: dict[str, tuple[float, dict]] = {}
                for candidate in candidates:
                    score = SequenceMatcher(None, span, candidate).ratio()
                    for name, medicine in compact_names[candidate].items():
                        if score > scores.get(name, (0, {}))[0]:
                            scores[name] = (score, medicine)
                ranked = sorted(scores.values(), key=lambda row: row[0], reverse=True)
                if ranked and ranked[0][0] >= .90 and (len(ranked) == 1 or ranked[0][0] - ranked[1][0] >= .04):
                    medicine = ranked[0][1]
                    matches[medicine["nome"]] = medicine
                    if observed is not None:
                        observed.append(" ".join(words[start:end]))
    return matches


def recovery_windows(text: str):
    # Scan the entire document, including a single long line. Overlap preserves
    # names near a window boundary. Generation receives only bounded excerpts.
    for line in text.splitlines():
        for offset in range(0, len(line), MAX_OCR_CHARS - 96):
            yield line[offset:offset + MAX_OCR_CHARS]


@dataclass
class RecoveryBudget:
    max_calls: int = 4
    milliseconds: int = 1500
    calls: int = 0
    started: float = field(default_factory=time.monotonic)

    def available(self) -> bool:
        return self.calls < self.max_calls and (time.monotonic() - self.started) * 1000 < self.milliseconds


def corroborate_lora_name(
    texto_ocr: str, suggest: Callable[[str], str | None],
    budget: RecoveryBudget | None = None,
) -> dict | None:
    budget = budget or RecoveryBudget(settings.lora_max_calls, settings.lora_budget_ms)
    # Multiple distinct identities on packaging are ambiguous; do not let the
    # generator select one and attach another medicine's dose to it.
    windows = []
    try:
        for window in recovery_windows(texto_ocr):
            if not budget.available():
                return None
            observed = []
            matches = evidence_candidates(window, budget, observed)
            windows.append((window, matches, observed))
    except TimeoutError:
        return None
    all_names = set(exact_catalog_identities(normalize_ocr(texto_ocr))).union(*(set(matches) for _, matches, _ in windows))
    if len(all_names) != 1:
        return None
    for window, matches, observed in windows:
        if not matches or not budget.available():
            continue
        budget.calls += 1
        token = GENERATION_DEADLINE.set(budget.started + budget.milliseconds / 1000)
        try:
            # Long legal paragraphs dilute generation. Focus on the observed
            # OCR span, never substitute the catalog answer into the prompt.
            excerpt = max(observed, key=len) if len(window) > 128 and observed else window
            suggestion = suggest(excerpt)
        finally:
            GENERATION_DEADLINE.reset(token)
        if (time.monotonic() - budget.started) * 1000 >= budget.milliseconds:
            return None
        if not suggestion or not re.fullmatch(r"[^\W\d_]+(?:[ -][^\W\d_]+)*", suggestion):
            continue
        proposed = lora_catalog()[0].get(normalize_ocr(suggestion))
        if proposed and proposed["nome"] in matches:
            return matches[proposed["nome"]]
    return None


DOSAGE_CANDIDATE_RE = re.compile(
    r"\b(\d{1,5}(?:[.,]\d+)?)\s*(MCG|MG|G|ML|UI)(?:\s*/\s*(\d{1,3}\s*)?(MCG|MG|G|ML|DOSE))?\b"
)


def extract_all_dosages(normalized_text: str) -> list[tuple[str, int]]:
    candidates: list[tuple[str, int]] = []
    for match in DOSAGE_CANDIDATE_RE.finditer(normalized_text):
        number = match.group(1).replace(",", ".")
        unit = match.group(2).lower()
        denom_num = re.sub(r"\s+", "", match.group(3) or "")
        denom_unit = (match.group(4) or "").lower()
        value = f"{number}{unit}/{denom_num}{denom_unit}" if denom_unit else f"{number}{unit}"
        priority = 2 if (denom_unit or unit != "ml") else 1
        candidates.append((value, priority))
    return candidates


def resolve_prioritized_dosage(candidates: list[tuple[str, int]]) -> Optional[str]:
    if not candidates:
        return None
    top_priority = max(priority for _, priority in candidates)
    distinct = {value for value, priority in candidates if priority == top_priority}
    return next(iter(distinct)) if len(distinct) == 1 else None


def extract_dosage(normalized_text: str) -> Optional[str]:
    return resolve_prioritized_dosage(extract_all_dosages(normalized_text))


def extract_expiration(normalized_text: str, today: Optional[date] = None) -> tuple[Optional[str], bool]:
    # Uma data so e validade se estiver explicitamente rotulada. Isso impede
    # confundir lote, fabricacao ou outros numeros da embalagem com vencimento.
    match = re.search(
        r"\b(?:VALIDADE|VALID|VAL|VENCIMENTO|VENC|VTO|EXPIRA|EXP|V)\s*[:.\-]?\s*"
        r"(0?[1-9]|1[0-2])\s*[/\-. ]\s*(\d{2}|\d{4})\b",
        normalized_text,
    )
    if not match:
        return None, False

    month = int(match.group(1))
    year = int(match.group(2))
    if year < 100:
        year += 2000
    expiration = date(year, month, calendar.monthrange(year, month)[1])
    reference = today or date.today()
    return f"{month:02d}/{year:04d}", expiration < reference


def allergy_matches(allergy: str, medicine: dict) -> bool:
    targets = (medicine["nome"], *medicine["aliases"], *medicine["componentes"])
    return any(_similarity(allergy, target) >= 0.78 for target in targets)


def parse_verification(
    texto_ocr: str,
    allergies: list[str],
    today: Optional[date] = None,
    *,
    suggest_name: Callable[[str], str | None] | None = None,
) -> VerificarResponse:
    normalized = normalize_ocr(texto_ocr)
    medicine = find_medicine(normalized)
    if medicine is None and suggest_name is not None:
        medicine = corroborate_lora_name(texto_ocr, suggest_name)
    # Identity is checked against the entire document above. Strength must come
    # from its own block, not the first measurement in administrative text.
    dosage = verification_dosage(texto_ocr, medicine) if medicine else None
    validity, expired = extract_expiration(normalized, today=today)

    if medicine is None:
        return VerificarResponse(
            nome="Medicamento não identificado com segurança",
            miligramas=dosage,
            validade=validity,
            vencido=expired,
            mensagem="Não consegui identificar o medicamento com segurança. Reposicione a caixa e tente novamente.",
            perigo=expired,
        )

    allergy_alert = any(allergy_matches(allergy, medicine) for allergy in allergies)
    messages = []
    if dosage is None:
        messages.append("Dosagem não encontrada na imagem.")
    if validity is None:
        messages.append("Validade não encontrada na imagem.")
    if allergy_alert:
        messages.insert(0, "Alerta de alergia detectado.")
    if expired:
        messages.insert(0, "Medicamento vencido.")

    return VerificarResponse(
        nome=medicine["nome"],
        miligramas=dosage,
        validade=validity,
        vencido=expired,
        alerta_alergia=allergy_alert,
        componentes=list(medicine["componentes"]),
        posologia=None,
        mensagem=" ".join(messages) or None,
        perigo=expired or allergy_alert,
    )


def extract_recipe_frequency(normalized_text: str) -> str:
    patterns = (
        r"\b\d{1,2}\s*/\s*\d{1,2}\s*H\b",
        r"\bA CADA \d{1,2} HORAS?\b",
        r"\bDE \d{1,2} EM \d{1,2} HORAS?\b",
        r"\b(?:\d{1,2}|UMA|DUAS|TRES)\s*(?:X|VEZ|VEZES)\s*(?:AO|POR)\s*DIA\b",
        r"\b(?:PELA MANHA|A NOITE|AO DEITAR|ANTES DE DORMIR)\b",
    )
    for pattern in patterns:
        match = re.search(pattern, normalized_text)
        if match:
            return match.group(0).lower()
    return "frequência não identificada"


def extract_recipe_duration(normalized_text: str) -> Optional[str]:
    match = re.search(
        r"\b(?:POR|DURANTE|POR ATE)\s+(\d{1,3}\s+(?:DIAS?|SEMANAS?|MESES?))\b",
        normalized_text,
    )
    return match.group(1).lower() if match else None


def extract_recipe_instructions(normalized_text: str) -> Optional[str]:
    instructions: list[str] = []
    administration = re.search(
        r"\b(?:TOMAR|USAR|APLICAR|INGERIR)\s+"
        r"(?:\d{1,2}|UM|UMA|MEIO|MEIA)\s+"
        r"(?:COMPRIMIDOS?|CP|CAPSULAS?|CAP|GOTAS?|ML|DOSES?|PUFFS?)\b",
        normalized_text,
    )
    if administration:
        instructions.append(administration.group(0).lower())

    context = re.search(
        r"\b(?:APOS AS? REFEICOES?|ANTES DAS? REFEICOES?|EM JEJUM|"
        r"PELA MANHA|A NOITE|AO DEITAR|ANTES DE DORMIR)\b",
        normalized_text,
    )
    if context:
        value = context.group(0).lower()
        if value not in instructions:
            instructions.append(value)
    return ", ".join(instructions) or None


_STANDALONE_NUMBER_RE = re.compile(r"^[0-9OIL]{1,5}(?:[.,][0-9OIL]{1,3})?$")
_STANDALONE_UNIT_RE = re.compile(
    r"^(?:MCG|MG|G|ML|UI|RNG|M9|MQ)(?:\s*/\s*(?:\d{1,3}\s*)?(?:MCG|MG|G|ML|DOSE))?$"
)
_PACKAGE_DESCRIPTOR_RE = re.compile(
    r"^(?:MEDICAMENTO\s+GENERICO|GENERICO|SIMILAR|REFERENCIA|LEI\s+N.*|"
    r"USO\s+(?:ORAL|ADULTO|PEDIATRICO|ADULTO\s+E\s+PEDIATRICO|TOPICO|EXTERNO|DERMATOLOGICO|OFTALMICO|NASAL)|"
    r"VIA\s+(?:ORAL|TOPICA)|SOLUCAO\s+(?:ORAL|INJETAVEL|NASAL|OFTALMICA)|"
    r"SUSPENSAO\s+ORAL|EMULSAO\s+ORAL|GOTAS|XAROPE|CREME|POMADA|GEL|SPRAY|AEROSSOL|"
    r"COMPRIMIDOS?(?:\s+(?:REVESTIDOS?|EFERVESCENTES?|MASTIGAVEIS?|SUBLINGUAIS?|SIMPLES|ORODISPERSIVEIS?))?|"
    r"CAPSULAS?(?:\s+(?:DURAS?|MOLES?|GELATINOSAS?))?|"
    r"(?:(?:CONTEM|EMBALAGEM\s+COM)\s+)?\d{1,3}\s+"
    r"(?:COMPRIMIDOS?|CAPSULAS?|DRAGEAS?|AMPOLAS?|FLACONETES?|FRASCOS?|SACHES?|ENVELOPES?|ADESIVOS?|UNIDADES?)"
    r"(?:\s+(?:REVESTIDOS?|DURAS?|MOLES?))?)$"
)
_PURE_STANDALONE_DOSAGE_RE = re.compile(
    r"^\d{1,5}(?:[.,]\d+)?\s*(?:MCG|MG|G|ML|UI)(?:\s*/\s*(?:\d{1,3}\s*)?(?:MCG|MG|G|ML|DOSE))?$"
)


def is_package_descriptor_line(normalized: str) -> bool:
    if re.match(r"^(?:\(\d{1,3}\)|\[\d{1,3}\]|\d{1,3}\s*[.)\-:]|[A-Z][.)]|[-*•●▪‣–—])\s*", normalized):
        return False
    return bool(_PACKAGE_DESCRIPTOR_RE.match(normalized.strip()))


def medication_lines(text: str) -> list[tuple[str, str]]:
    raw_segments = [
        raw.strip()
        for raw in re.split(
            r"[\n;]+|\s+(?=(?:\(\d{1,3}\)|\[\d{1,3}\]|"
            r"\d{1,3}\s*[.)\-:]|[A-Za-z][.)]|[-*•●▪‣–—])\s*[^\W\d_])",
            text,
        )
        if raw.strip()
    ]
    merged: list[str] = []
    idx = 0
    while idx < len(raw_segments):
        current = raw_segments[idx]
        nxt = raw_segments[idx + 1] if idx + 1 < len(raw_segments) else None
        if (
            nxt is not None
            and _STANDALONE_NUMBER_RE.match(strip_accents(current).upper())
            and _STANDALONE_UNIT_RE.match(strip_accents(nxt).upper())
        ):
            merged.append(f"{current} {nxt}")
            idx += 2
        else:
            merged.append(current)
            idx += 1
    return [
        (raw, normalized)
        for raw in merged
        if (normalized := normalize_ocr(raw))
    ]


def strip_item_marker(text: str) -> str:
    return re.sub(
        r"^\s*(?:\(\d{1,3}\)|\[\d{1,3}\]|\d{1,3}\s*[.)\-:]|[A-Z][.)]|[-*•●▪‣–—])\s*",
        "",
        text,
    ).strip()


def is_medication_continuation(normalized: str) -> bool:
    """Only explicit dose/usage lines may extend a preceding identity block.

    Free text (including a standalone unknown product name) starts a barrier.
    Markers do not turn unknown names into administration instructions.
    """
    text = strip_item_marker(normalized)
    text = re.sub(r"^(?:DOSE|DOSAGEM|POSOLOGIA|FREQUENCIA)\s*[:\-]?\s*", "", text)
    dose = re.match(r"\d{1,5}(?:[.,]\d+)?\s*(?:MCG|MG|G|ML|UI)\b", text)
    if dose:
        rest = text[dose.end():].strip()
        if not rest:
            return True
        # Preserve printed concentrations and administration after a strength.
        rest = re.sub(r"^/\s*(?:\d+(?:[.,]\d+)?\s*)?(?:MCG|MG|ML|G|DOSE)\b", "", rest).strip()
        if not rest:
            return True
        text = rest
    if dose and re.match(r"^(?:VALIDADE|VALID|VAL|VENCIMENTO|VENC|VTO|EXPIRA|EXP|V)\b", text):
        return True
    return bool(re.match(
        r"^(?:(?:TOMAR|USAR|APLICAR|INGERIR)\b|A CADA\s+\d|DE\s+\d+\s+EM\s+\d|"
        r"\d{1,2}\s*/\s*\d{1,2}\s*H\b|"
        r"(?:\d{1,2}|UMA|DUAS|TRES)\s*(?:X|VEZ|VEZES|COMPRIMIDOS?|CP|CAPSULAS?|GOTAS?)\b|"
        r"POR\s+\d|DURANTE\s+\d|APOS\b|ANTES\b|EM JEJUM\b|PELA MANHA\b|"
        r"A NOITE\b|AO DEITAR\b|USO ORAL\b|VIA ORAL\b|"
        r"COMPRIMIDOS?\b|CAPSULAS?\b)",
        text,
    ))


def identity_suffix(normalized: str, medicine: dict) -> str | None:
    """Find an observed identity span without substituting a catalog answer."""
    spans = {normalize_ocr(name) for name in (medicine["nome"], *medicine["aliases"])}
    observed: list[str] = []
    evidence_candidates(normalized, observed=observed)
    for span in set(observed):
        if medicine["nome"] in evidence_candidates(span):
            spans.add(span)
    starts = []
    for span in spans:
        pattern = r"(?<![A-Z0-9])" + r"\W+".join(map(re.escape, span.split())) + r"(?![A-Z0-9])"
        if match := re.search(pattern, normalized):
            starts.append(match.start())
    return normalized[min(starts):] if starts else None


def identity_context(
    lines: list[tuple[str, str]], index: int, medicine: dict, boundaries: list[bool],
    first: str | None = None, *, package_layout: bool = False,
) -> str:
    # Remove text before the identity even when OCR joins a preface and name
    # onto one line. In package layout, safely bridge neutral packaging descriptors.
    if first is None:
        first = identity_suffix(lines[index][1], medicine)
    if first is None:
        return ""
    block: list[str] = []
    if package_layout:
        for back in range(index - 1, max(-1, index - 4), -1):
            prev_norm = lines[back][1]
            if identity_suffix(prev_norm, medicine) is not None or find_medicine(prev_norm):
                break
            if is_package_descriptor_line(prev_norm):
                continue
            if _PURE_STANDALONE_DOSAGE_RE.match(prev_norm.strip()):
                before = lines[back - 1][1] if back - 1 >= 0 else None
                if before is None or is_package_descriptor_line(before):
                    block.append(prev_norm)
                break
            break
    block.append(first)
    lookahead = 7 if package_layout else 4
    for following in range(index + 1, min(index + lookahead, len(lines))):
        line_norm = lines[following][1]
        allowed_descriptor = package_layout and is_package_descriptor_line(line_norm)
        if boundaries[following] and not allowed_descriptor:
            break
        block.append(strip_item_marker(line_norm))
    return " ".join(block)


def verification_dosage(text: str, medicine: dict) -> str | None:
    lines = medication_lines(text)
    boundaries = [not is_medication_continuation(line) for _, line in lines]
    candidates: list[tuple[str, int]] = []
    index = 0
    while index < len(lines):
        normalized = lines[index][1]
        if (first := identity_suffix(normalized, medicine)) is not None:
            context = identity_context(lines, index, medicine, boundaries, first, package_layout=True)
            candidates.extend(extract_all_dosages(context))
            index += 1
            continue
        if index + 1 < len(lines) and find_medicine(normalized) is None and find_medicine(lines[index + 1][1]) is None:
            combined_norm = f"{normalized} {lines[index + 1][1]}"
            if (combined_first := identity_suffix(combined_norm, medicine)) is not None:
                synthetic_lines = [
                    *lines[:index],
                    (f"{lines[index][0]} {lines[index + 1][0]}", combined_norm),
                    *lines[index + 2:],
                ]
                synthetic_boundaries = [
                    *boundaries[:index],
                    True,
                    *boundaries[index + 2:],
                ]
                context = identity_context(
                    synthetic_lines, index, medicine, synthetic_boundaries, combined_first, package_layout=True,
                )
                candidates.extend(extract_all_dosages(context))
                index += 2
                continue
        index += 1
    return resolve_prioritized_dosage(candidates)


def parse_recipe(
    texto_ocr: str, *, suggest_name: Callable[[str], str | None] | None = None,
) -> ReceitaResponse:
    budget = RecoveryBudget(settings.lora_max_calls, settings.lora_budget_ms)
    lines = medication_lines(texto_ocr)
    normalized_lines = [normalized for _, normalized in lines]
    detected_medicines = [find_medicine(line) for line in normalized_lines]
    # Bound candidate preparation as well as generation. Unprocessed lines
    # remain barriers so an exhausted budget cannot leak their dose backwards.
    candidate_boundaries = [True] * len(lines)
    for index, (raw, _) in enumerate(lines):
        if not budget.available():
            break
        try:
            evidence = bool(evidence_candidates(raw, budget))
            candidate_boundaries[index] = evidence or not is_medication_continuation(lines[index][1])
        except TimeoutError:
            break
    if suggest_name is not None:
        for index, (raw_line, _) in enumerate(lines):
            if detected_medicines[index] is None and candidate_boundaries[index] and budget.available():
                detected_medicines[index] = corroborate_lora_name(raw_line, suggest_name, budget)
    medicines: list[MedicamentoReceita] = []
    seen: set[str] = set()
    boundaries = [boundary or detected is not None
                  for boundary, detected in zip(candidate_boundaries, detected_medicines)]
    for line_index, medicine in enumerate(detected_medicines):
        if not medicine or medicine["nome"] in seen:
            continue
        seen.add(medicine["nome"])

        # Prescrições costumam colocar nome, dosagem e modo de uso em linhas
        # diferentes. Junta até três linhas seguintes, parando antes do
        # próximo medicamento para não atribuir uma dose ao item errado.
        context = identity_context(lines, line_index, medicine, boundaries)

        medicines.append(
            MedicamentoReceita(
                nome=medicine["nome"],
                dosagem=extract_dosage(context) or "dosagem não identificada",
                frequencia=extract_recipe_frequency(context),
                duracao=extract_recipe_duration(context),
                instrucoes=extract_recipe_instructions(context),
            )
        )

    horarios = sorted(set(re.findall(r"\b(?:[01]\d|2[0-3]):[0-5]\d\b", texto_ocr)))
    summary_parts = [
        " ".join(
            part
            for part in (medicine.nome, medicine.dosagem, medicine.frequencia, medicine.duracao, medicine.instrucoes)
            if part
        )
        for medicine in medicines
    ]
    if not medicines:
        return ReceitaResponse(
            medicamentos=[],
            posologia_completa="",
            horarios=horarios,
            mensagem="Não consegui identificar medicamentos na receita. Tente outra foto com melhor foco.",
        )
    incomplete = any(
        medicine.dosagem == "dosagem não identificada"
        or medicine.frequencia == "frequência não identificada"
        for medicine in medicines
    )
    return ReceitaResponse(
        medicamentos=medicines,
        posologia_completa=". ".join(summary_parts),
        horarios=horarios,
        mensagem=(
            "Receita lida, mas alguns dados não foram identificados. Confirme na receita original."
            if incomplete
            else "Receita processada com sucesso."
        ),
    )


async def init_db_pool() -> None:
    global db_pool
    if not settings.database_url:
        logger.warning("DATABASE_URL ausente; usando armazenamento temporario em memoria")
        return
    try:
        db_pool = await asyncpg.create_pool(settings.database_url, min_size=1, max_size=5, command_timeout=10)
        async with db_pool.acquire() as connection:
            await connection.execute(
                """
                CREATE TABLE IF NOT EXISTS alergias_guiamed (
                    device_id TEXT NOT NULL,
                    nome_alergia TEXT NOT NULL,
                    criada_em TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (device_id, nome_alergia)
                )
                """
            )
        logger.info("PostgreSQL conectado")
    except Exception as exc:
        db_pool = None
        logger.warning("PostgreSQL indisponivel; usando memoria: %s", exc)


async def close_db_pool() -> None:
    global db_pool
    if db_pool is not None:
        await db_pool.close()
        db_pool = None


async def get_allergies(device_id: str) -> list[str]:
    if db_pool is not None:
        async with db_pool.acquire() as connection:
            rows = await connection.fetch(
                "SELECT nome_alergia FROM alergias_guiamed WHERE device_id = $1 ORDER BY criada_em",
                device_id,
            )
        return [row["nome_alergia"] for row in rows]
    with memory_lock:
        return sorted(memory_allergies.get(device_id, set()))


async def save_allergy(device_id: str, allergy: str) -> list[str]:
    cleaned = strip_accents(allergy).strip().lower()
    if db_pool is not None:
        async with db_pool.acquire() as connection:
            await connection.execute(
                """
                INSERT INTO alergias_guiamed (device_id, nome_alergia)
                VALUES ($1, $2)
                ON CONFLICT (device_id, nome_alergia) DO NOTHING
                """,
                device_id,
                cleaned,
            )
        return await get_allergies(device_id)
    with memory_lock:
        memory_allergies.setdefault(device_id, set()).add(cleaned)
        return sorted(memory_allergies[device_id])


def anonymize_device_id(device_id: str) -> str:
    return hashlib.sha256(device_id.encode("utf-8")).hexdigest()


async def save_rag_audit(
    device_id: str,
    question: str,
    sources: list[str],
    latency_ms: float,
    cost_usd: float,
    metadata: dict[str, object],
    response_text: str | None = None,
) -> RagAuditReceipt:
    """Registra a resposta fictícia e metadados sem persistir pergunta ou device_id."""
    requester_hash = anonymize_device_id(device_id)
    query_fingerprint = hashlib.sha256(question.encode("utf-8")).hexdigest()
    response_fingerprint = (
        hashlib.sha256(response_text.encode("utf-8")).hexdigest()
        if response_text is not None
        else None
    )
    audit_payload = {
        "fontes": list(sources),
        "resposta": response_text,
        "resposta_sha256": response_fingerprint,
        "execucao": metadata,
    }
    if db_pool is not None:
        try:
            async with db_pool.acquire() as connection:
                row = await connection.fetchrow(
                    """
                    SELECT guiamed_api.record_consultation(
                        $1, $2, $3, $4::jsonb, $5, $6
                    ) AS audit_id
                    """,
                    requester_hash,
                    "rag_local",
                    query_fingerprint,
                    json.dumps(audit_payload, ensure_ascii=False, allow_nan=False),
                    latency_ms,
                    cost_usd,
                )
            if row is not None:
                return RagAuditReceipt(id=str(row["audit_id"]), backend="postgresql", persistida=True)
        except Exception as exc:
            logger.warning("Auditoria PostgreSQL indisponível; usando memória: %s", type(exc).__name__)

    audit_id = str(uuid.uuid4())
    with memory_lock:
        memory_audit.append(
            {
                "audit_id": audit_id,
                "requester_hash": requester_hash,
                "operation": "rag_local",
                "query_fingerprint": query_fingerprint,
                "sources": list(sources),
                "response": response_text,
                "response_sha256": response_fingerprint,
                "execution": metadata,
                "requested_at": datetime.now().isoformat(),
                "latency_ms": latency_ms,
                "cost_usd": cost_usd,
            }
        )
    return RagAuditReceipt(id=audit_id, backend="memoria", persistida=False)


@asynccontextmanager
async def lifespan(application: FastAPI):
    await init_db_pool()
    application.state.local_lora = None
    application.state.lora_status = ("legacy-incompatible" if settings.lora_adapter_dir is None or settings.lora_gguf_path else "unavailable") if settings.lora_enabled else "disabled"
    try:
        await asyncio.to_thread(lora_catalog)
        await asyncio.to_thread(recovery_index)
        try:
            await asyncio.to_thread(get_local_rag)
        except Exception as error:
            logger.warning("RAG local indisponível no startup (%s)", type(error).__name__)
        if settings.lora_enabled:
            local_paths = (settings.lora_gguf_path,) if settings.lora_gguf_path else (None,)
            for model_path in local_paths:
                runtime_name = "GGUF" if model_path else "FP32"
                try:
                    application.state.local_lora = await asyncio.to_thread(
                        LocalLora.load, model_path, settings.lora_threads,
                        **({"adapter_dir": settings.lora_adapter_dir} if settings.lora_adapter_dir else {}),
                    )
                except Exception as error:
                    logger.warning("LoRA %s indisponível no startup (%s)", runtime_name, type(error).__name__)
                else:
                    application.state.lora_status = "ready"
                    logger.info("LoRA local carregado (%s)", runtime_name)
                    break
        yield
    finally:
        application.state.local_lora = None
        await close_db_pool()


app = FastAPI(
    title="GuiaMed API",
    description="Identificação conservadora de medicamentos por OCR para acessibilidade",
    version="1.0.0",
    lifespan=lifespan,
)
if settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type"],
    )


@app.middleware("http")
async def ai_runtime_status(request: Request, call_next):
    response = await call_next(request)
    runtime = getattr(request.app.state, "local_lora", None)
    response.headers["X-GuiaMed-IA"] = getattr(request.app.state, "lora_status", "not-started")
    if runtime is not None:
        response.headers["X-GuiaMed-IA-Last-Result"] = runtime.last_result
    return response


@app.get("/health", response_model=HealthResponse, tags=["Sistema"])
async def health_check() -> HealthResponse:
    return HealthResponse(
        status="ok",
        database="connected" if db_pool is not None else "memory",
        timestamp=datetime.now(),
    )


@app.post("/verificar", response_model=VerificarResponse, tags=["Medicamentos"])
async def verificar_medicamento(request: VerificarRequest, http_request: Request) -> VerificarResponse:
    try:
        allergies = await get_allergies(request.device_id)
        lora = getattr(http_request.app.state, "local_lora", None)
        return await asyncio.to_thread(
            parse_verification, request.texto_ocr, allergies, suggest_name=lora.suggest if lora else None,
        )
    except Exception as exc:
        logger.exception("Falha ao verificar medicamento")
        raise HTTPException(status_code=500, detail="Erro ao verificar medicamento") from exc


@app.post("/alergias", response_model=AlergiaResponse, tags=["Alergias"])
async def cadastrar_alergia(request: AlergiaRequest) -> AlergiaResponse:
    try:
        allergies = await save_allergy(request.device_id, request.nome_alergia)
        return AlergiaResponse(
            sucesso=True,
            mensagem=f"Alergia a {request.nome_alergia.strip()} registrada com sucesso.",
            alergias_registradas=allergies,
        )
    except Exception as exc:
        logger.exception("Falha ao cadastrar alergia")
        raise HTTPException(status_code=500, detail="Erro ao cadastrar alergia") from exc


@app.post("/receita", response_model=ReceitaResponse, tags=["Receitas"])
async def processar_receita(request: ReceitaRequest, http_request: Request) -> ReceitaResponse:
    try:
        lora = getattr(http_request.app.state, "local_lora", None)
        return await asyncio.to_thread(
            parse_recipe, request.texto_ocr, suggest_name=lora.suggest if lora else None,
        )
    except Exception as exc:
        logger.exception("Falha ao processar receita")
        raise HTTPException(status_code=500, detail="Erro ao processar receita") from exc


@app.post("/rag/consultar", response_model=RagResponse, tags=["IA local"])
async def consultar_rag_local(request: RagRequest) -> RagResponse:
    """Busca + geração local fora do event loop; nunca executa SQL do cliente."""
    started = time.perf_counter()
    costs = {
        "escopo_custo_usd": "cobranca_de_apis_externas",
        "energia_medida": False,
        "energia_usd": None,
        "hardware_usd": None,
    }
    try:
        result = await asyncio.to_thread(
            lambda: get_local_rag().answer(request.pergunta, prompt_version=request.prompt_version),
        )
        if result.prompt_version != request.prompt_version:
            raise ValueError("RAG prompt version mismatch")
        total_ms = (time.perf_counter() - started) * 1000
        # Whitelist metadata. raw_response, prompts and questions never enter audit/logs.
        metadata = {
            "busca_ms": result.busca_ms,
            "geracao_ms": result.geracao_ms,
            "load_ms": result.load_ms,
            "total_ms": total_ms,
            "input_tokens": result.input_tokens,
            "output_tokens": result.output_tokens,
            "model": result.model,
            "revision": result.revision,
            "mode": result.mode,
            "fallback": result.mode == "fallback_template",
            "fallback_reason": result.fallback_reason,
            "prompt_version": result.prompt_version,
            "prompt_sha256": result.prompt_sha256,
            "generation_attempted": result.generation_attempted,
            "llm_called": result.llm_called,
            "generator_backend": result.generator_backend,
            "citation_origin": result.citation_origin,
            "decoding": result.decoding,
            "custos": costs,
        }
        metadata["geracao_llm_real"] = (
            result.mode == "llm_grounded" and metadata["llm_called"] and
            metadata["generator_backend"] == "transformers" and (result.output_tokens or 0) > 0
        )
        response = RagResponse(
            resposta=result.response,
            fontes=list(result.sources),
            medicamento=result.medicine,
            confianca=result.confidence,
            latencia_ms=total_ms,
            custo_usd=result.cost_usd,
            auditoria_id="pending",
            auditoria=RagAuditReceipt(id="pending", backend="memoria", persistida=False),
            entrada_hostil_detectada=result.hostile_input_detected,
            consulta_sha256=hashlib.sha256(request.pergunta.encode("utf-8")).hexdigest(),
            **metadata,
        )
    except Exception as exc:
        total_ms = (time.perf_counter() - started) * 1000
        logger.warning("Geração RAG indisponível (%s)", type(exc).__name__)
        receipt = await save_rag_audit(
            request.device_id, request.pergunta, [], total_ms, 0.0,
            {"mode": "unavailable", "fallback": False, "prompt_version": request.prompt_version,
             "total_ms": total_ms, "custos": costs},
        )
        raise HTTPException(status_code=503, detail={
            "mensagem": "A geração local está indisponível. Tente novamente mais tarde.",
            "mode": "unavailable", "fallback": False,
            "prompt_version": request.prompt_version,
            "latencia_ms": total_ms, "custo_usd": 0.0, "custos": costs,
            "auditoria_id": receipt.id, "auditoria": receipt.model_dump(),
        }) from None
    receipt = await save_rag_audit(
        request.device_id, request.pergunta, response.fontes, response.latencia_ms,
        response.custo_usd, metadata, response_text=response.resposta,
    )
    return response.model_copy(update={"auditoria_id": receipt.id, "auditoria": receipt})


@app.post("/rag/demo/alergia", tags=["IA local"])
async def consultar_alergia_demo(
    authorization: str = Header(default=""),
) -> dict[str, str | bool]:
    """Consulta acadêmica de um registro fictício fixo, habilitada por tokens locais."""
    token_a = os.environ.get("GUIAMED_DEMO_TOKEN_A", "")
    token_b = os.environ.get("GUIAMED_DEMO_TOKEN_B", "")
    if min(len(token_a), len(token_b)) < 32 or token_a == token_b:
        raise HTTPException(status_code=404, detail="Demonstração desabilitada")
    scheme, _, token = authorization.partition(" ")
    profile = next(
        (name for name, secret in (("a", token_a), ("b", token_b))
         if hmac.compare_digest(token.encode(), secret.encode())),
        None,
    )
    if scheme.lower() != "bearer" or profile is None:
        raise HTTPException(status_code=401, detail="Credencial inválida")
    if db_pool is None:
        raise HTTPException(status_code=503, detail="Banco indisponível")

    requester = hashlib.sha256(f"guiamed-demo-academica-perfil-{profile}".encode()).hexdigest()
    fingerprint = hashlib.sha256(b"demo-alergia-perfil-a").hexdigest()
    allowed = profile == "a"
    operation = "demo_alergia_permitida" if allowed else "demo_alergia_negada"
    async with db_pool.acquire() as connection:
        async with connection.transaction():
            await connection.execute("SET LOCAL ROLE rl_app_ia")
            if allowed:
                found = await connection.fetchval(
                    "SELECT guiamed_api.check_allergy($1, $2)",
                    requester, "substancia-ficticia-demo",
                )
            audit_id = await connection.fetchval(
                """SELECT guiamed_api.record_consultation(
                    $1, $2, $3, '["demo:alergia:perfil-a"]'::jsonb, 0, 0
                )""",
                requester, operation, fingerprint,
            )
    if not allowed:
        raise HTTPException(status_code=403, detail={
            "mensagem": "Acesso negado", "auditoria_id": str(audit_id),
        })
    return {"alergia_registrada": found, "auditoria_id": str(audit_id)}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=True,
        log_level=settings.log_level.lower(),
    )
