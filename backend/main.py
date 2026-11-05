"""API V1 do GuiaMed.

O parser e deliberadamente conservador: ele nunca inventa dosagem ou validade
quando esses dados nao aparecem no OCR. O PostgreSQL e usado quando
``DATABASE_URL`` esta configurada; em desenvolvimento, a API continua utilizavel
com alergias mantidas em memoria durante a execucao.
"""

from __future__ import annotations

import asyncio
import calendar
import json
import logging
import re
import unicodedata
from contextlib import asynccontextmanager
from datetime import date, datetime
from difflib import SequenceMatcher
from functools import lru_cache
from pathlib import Path
from typing import Optional

import asyncpg
from asyncpg.pool import Pool
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: Optional[str] = None
    api_host: str = "0.0.0.0"
    api_port: int = 8000
    log_level: str = "INFO"
    cors_origins: list[str] = Field(default_factory=list)

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")


settings = Settings()
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("guiamed")

db_pool: Optional[Pool] = None
memory_allergies: dict[str, set[str]] = {}
memory_lock = asyncio.Lock()


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

    def fix_measure(match: re.Match[str]) -> str:
        number = match.group(1).replace("O", "0").replace("I", "1").replace("L", "1")
        return f"{number} {match.group(2)}"

    text = re.sub(r"\b([0-9OIL]+(?:[.,][0-9OIL]+)?)\s*(MCG|MG|ML|G)\b", fix_measure, text)
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


def find_dcb_medicine(normalized_text: str) -> Optional[dict]:
    exact_index, single_word_index = load_dcb_catalog()
    if not exact_index:
        return None

    words = [
        word
        for word in re.findall(r"[A-Z0-9]{2,}", normalized_text)
        if any(char.isalpha() for char in word)
    ]

    # Prefere a denominação exata mais longa. Assim, por exemplo,
    # "rosuvastatina cálcica" ganha de "rosuvastatina".
    exact_matches: list[tuple[int, dict]] = []
    for size in range(1, min(6, len(words)) + 1):
        for index in range(len(words) - size + 1):
            candidate = " ".join(words[index : index + size])
            medicine = exact_index.get(candidate)
            if medicine is not None:
                exact_matches.append((len(candidate), medicine))
    if exact_matches:
        return max(exact_matches, key=lambda match: match[0])[1]

    # A busca aproximada é deliberadamente restrita a palavras longas e exige
    # vantagem clara sobre o segundo resultado, evitando adivinhar remédios.
    candidates = {
        word
        for word in words
        if len(word) >= 7 and word not in OCR_NON_MEDICINE_WORDS
    }
    best_medicine: Optional[dict] = None
    best_score = 0.0
    second_score = 0.0
    for candidate in candidates:
        tolerance = max(2, round(len(candidate) * 0.18))
        for official_name, medicine in single_word_index:
            if abs(len(official_name) - len(candidate)) > tolerance:
                continue
            score = SequenceMatcher(None, official_name, candidate).ratio()
            if score > best_score:
                second_score = best_score
                best_score = score
                best_medicine = medicine
            elif medicine != best_medicine and score > second_score:
                second_score = score

    if best_score >= 0.86 and best_score - second_score >= 0.04:
        return best_medicine
    return None


def _similarity(left: str, right: str) -> float:
    left_normalized = normalize_ocr(left)
    right_normalized = normalize_ocr(right)
    if left_normalized in right_normalized or right_normalized in left_normalized:
        return 1.0
    return SequenceMatcher(None, left_normalized, right_normalized).ratio()


def find_medicine(normalized_text: str) -> Optional[dict]:
    words = re.findall(r"[A-Z]{3,}", normalized_text)
    candidates = words + [" ".join(words[index : index + 2]) for index in range(len(words) - 1)]
    best_medicine: Optional[dict] = None
    best_score = 0.0
    for medicine in MEDICINES:
        for alias in medicine["aliases"]:
            alias_normalized = normalize_ocr(alias)
            if alias_normalized in normalized_text:
                return medicine
            for candidate in candidates:
                score = SequenceMatcher(None, alias_normalized, candidate).ratio()
                if score > best_score:
                    best_score = score
                    best_medicine = medicine
    if best_score >= 0.86:
        return best_medicine
    dcb_medicine = find_dcb_medicine(normalized_text)
    if dcb_medicine is not None:
        return dcb_medicine
    return best_medicine if best_score >= 0.72 else None


def extract_dosage(normalized_text: str) -> Optional[str]:
    match = re.search(r"\b(\d{1,4}(?:[.,]\d+)?)\s*(MCG|MG|G|ML)\b", normalized_text)
    if not match:
        return None
    number = match.group(1).replace(",", ".")
    unit = match.group(2).lower()
    return f"{number}{unit}"


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
) -> VerificarResponse:
    normalized = normalize_ocr(texto_ocr)
    medicine = find_medicine(normalized)
    dosage = extract_dosage(normalized)
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


def parse_recipe(texto_ocr: str) -> ReceitaResponse:
    normalized_lines = [
        normalized
        for raw_line in (texto_ocr.splitlines() or [texto_ocr])
        if (normalized := normalize_ocr(raw_line))
    ]
    detected_medicines = [find_medicine(line) for line in normalized_lines]
    medicines: list[MedicamentoReceita] = []
    seen: set[str] = set()
    for line_index, medicine in enumerate(detected_medicines):
        if not medicine or medicine["nome"] in seen:
            continue
        seen.add(medicine["nome"])

        # Prescrições costumam colocar nome, dosagem e modo de uso em linhas
        # diferentes. Junta até três linhas seguintes, parando antes do
        # próximo medicamento para não atribuir uma dose ao item errado.
        block_lines = [normalized_lines[line_index]]
        for next_index in range(line_index + 1, min(line_index + 4, len(normalized_lines))):
            next_medicine = detected_medicines[next_index]
            if next_medicine and next_medicine["nome"] != medicine["nome"]:
                break
            block_lines.append(normalized_lines[next_index])
        context = " ".join(block_lines)

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
    async with memory_lock:
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
    async with memory_lock:
        memory_allergies.setdefault(device_id, set()).add(cleaned)
        return sorted(memory_allergies[device_id])


@asynccontextmanager
async def lifespan(_: FastAPI):
    await init_db_pool()
    yield
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


@app.get("/health", response_model=HealthResponse, tags=["Sistema"])
async def health_check() -> HealthResponse:
    return HealthResponse(
        status="ok",
        database="connected" if db_pool is not None else "memory",
        timestamp=datetime.now(),
    )


@app.post("/verificar", response_model=VerificarResponse, tags=["Medicamentos"])
async def verificar_medicamento(request: VerificarRequest) -> VerificarResponse:
    try:
        allergies = await get_allergies(request.device_id)
        return parse_verification(request.texto_ocr, allergies)
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
async def processar_receita(request: ReceitaRequest) -> ReceitaResponse:
    try:
        return parse_recipe(request.texto_ocr)
    except Exception as exc:
        logger.exception("Falha ao processar receita")
        raise HTTPException(status_code=500, detail="Erro ao processar receita") from exc


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=True,
        log_level=settings.log_level.lower(),
    )
