"""API V1 do GuiaMed.

O parser e deliberadamente conservador: ele nunca inventa dosagem ou validade
quando esses dados nao aparecem no OCR. O PostgreSQL e usado quando
``DATABASE_URL`` esta configurada; em desenvolvimento, a API continua utilizavel
com alergias mantidas em memoria durante a execucao.
"""

from __future__ import annotations

import asyncio
import calendar
import logging
import re
import unicodedata
from contextlib import asynccontextmanager
from datetime import date, datetime
from difflib import SequenceMatcher
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
)


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
        r"\b(?:VALIDADE|VALID|VAL|VENCIMENTO|VENC)\s*[:\-]?\s*(0?[1-9]|1[0-2])\s*[/\-. ]\s*(\d{2}|\d{4})\b",
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


def parse_recipe(texto_ocr: str) -> ReceitaResponse:
    medicines: list[MedicamentoReceita] = []
    seen: set[str] = set()
    for raw_line in texto_ocr.splitlines() or [texto_ocr]:
        normalized = normalize_ocr(raw_line)
        medicine = find_medicine(normalized)
        if not medicine or medicine["nome"] in seen:
            continue
        seen.add(medicine["nome"])
        dosage = extract_dosage(normalized) or "dosagem não identificada"
        frequency_match = re.search(r"\b(\d{1,2}\s*/\s*\d{1,2}\s*H|A CADA \d{1,2} HORAS?)\b", normalized)
        duration_match = re.search(r"\bPOR\s+(\d{1,3}\s+DIAS?)\b", normalized)
        instruction_match = re.search(r"\b(APOS AS? REFEICOES?|EM JEJUM|ANTES DE DORMIR)\b", normalized)
        medicines.append(
            MedicamentoReceita(
                nome=medicine["nome"],
                dosagem=dosage,
                frequencia=frequency_match.group(1).lower() if frequency_match else "frequência não identificada",
                duracao=duration_match.group(1).lower() if duration_match else None,
                instrucoes=instruction_match.group(1).lower() if instruction_match else None,
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
    return ReceitaResponse(
        medicamentos=medicines,
        posologia_completa=". ".join(summary_parts),
        horarios=horarios,
        mensagem="Receita processada com sucesso.",
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
