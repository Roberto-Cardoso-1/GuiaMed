# ============================================================================
# GUIAMED BACKEND - FastAPI Main Application
# Endpoints: /verificar, /alergias, /receitas, /alergias/{device_id}
# ============================================================================

import os
import logging
from contextlib import asynccontextmanager
from typing import Optional, List
from datetime import datetime

from fastapi import FastAPI, HTTPException, Depends, Request, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings
import asyncpg
from asyncpg.pool import Pool

# ============================================================================
# CONFIGURAÇÃO E SETTINGS
# ============================================================================

class Settings(BaseSettings):
    # Sem defaults de credencial: DATABASE_URL e PGCRYPTO_KEY sao OBRIGATORIOS
    # via .env (ver .env.example). Nenhum segredo fica versionado no codigo.
    database_url: str
    pgcrypto_key: str
    api_host: str = "0.0.0.0"
    api_port: int = 8000
    log_level: str = "INFO"
    cors_origins: List[str] = ["*"]

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"

settings = Settings()

# Configurar logging
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper()),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger("guiamed")

# ============================================================================
# POOL DE CONEXÕES POSTGRESQL
# ============================================================================

db_pool: Optional[Pool] = None

async def init_db_pool():
    global db_pool
    db_pool = await asyncpg.create_pool(
        settings.database_url,
        min_size=2,
        max_size=10,
        command_timeout=30,
        init=init_connection
    )
    logger.info("Pool de conexões PostgreSQL criado")

async def close_db_pool():
    if db_pool:
        await db_pool.close()
        logger.info("Pool de conexões fechado")

async def init_connection(conn: asyncpg.Connection):
    """Inicializa cada conexão: seta pgcrypto_key e prepara statements"""
    await conn.execute("SET app.pgcrypto_key = $1", settings.pgcrypto_key)
    logger.debug("Conexão inicializada com pgcrypto_key")

def get_db_pool() -> Pool:
    if not db_pool:
        raise HTTPException(status_code=503, detail="Banco não inicializado")
    return db_pool

# ============================================================================
# LIFESPAN (STARTUP/SHUTDOWN)
# ============================================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    await init_db_pool()
    yield
    # Shutdown
    await close_db_pool()

# ============================================================================
# APP FASTAPI
# ============================================================================

app = FastAPI(
    title="GuiaMed API",
    description="API para identificação de medicamentos por OCR + voz para pessoas cegas",
    version="1.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ============================================================================
# MODELOS PYDANTIC (REQUEST/RESPONSE)
# ============================================================================

class VerificarRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=200, description="ID único do dispositivo Android")
    texto_ocr: str = Field(..., min_length=1, description="Texto extraído pela câmera/OCR")
    tem_validade: bool = Field(False, description="Se o OCR capturou data de validade")
    data_validade: Optional[str] = Field(None, description="Data de validade no formato YYYY-MM-DD")

class VerificarResponse(BaseModel):
    sucesso: bool
    nome_comercial: Optional[str] = None
    principio_ativo: Optional[str] = None
    concentracao: Optional[str] = None
    forma_farmaceutica: Optional[str] = None
    tem_alergia: bool = False
    alergia_detalhe: Optional[str] = None
    tem_prescricao: bool = False
    prescricao_detalhe: Optional[str] = None
    validade_vencida: bool = False
    validade_dias_restantes: Optional[int] = None
    mensagem_voz: str

class AlergiaRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=200)
    texto_falado: str = Field(..., min_length=1, description="Texto falado pelo usuário (ex: 'Novalgina')")
    origem: str = Field("voz", description="Origem: 'voz', 'receita', 'manual'")

class AlergiaResponse(BaseModel):
    sucesso: bool
    mensagem: str
    principio_ativo_detectado: Optional[str] = None
    alergia_id: Optional[int] = None

class ReceitaRequest(BaseModel):
    device_id: str = Field(..., min_length=1, max_length=200)
    texto_ocr: str = Field(..., min_length=1, description="Texto completo da receita via OCR")

class ReceitaResponse(BaseModel):
    sucesso: bool
    medicamentos_processados: int
    alergias_extraidas: int
    mensagem_voz: str

class AlergiaListResponse(BaseModel):
    device_id: str
    alergias: List[dict]

class HealthResponse(BaseModel):
    status: str
    database: str
    timestamp: datetime

# ============================================================================
# DEPENDÊNCIA: Executar função PL/pgSQL obter_ou_criar_device
# ============================================================================

async def set_rls_context(pool: Pool, device_id: str) -> int:
    """Define o contexto RLS para a conexão atual via device_id"""
    async with pool.acquire() as conn:
        usuario_id = await conn.fetchval(
            "SELECT obter_ou_criar_device($1)", device_id
        )
        return usuario_id

# ============================================================================
# ENDPOINTS
# ============================================================================

@app.get("/health", response_model=HealthResponse, tags=["Sistema"])
async def health_check(pool: Pool = Depends(get_db_pool)):
    """Health check da API e banco"""
    try:
        async with pool.acquire() as conn:
            await conn.fetchval("SELECT 1")
        db_status = "connected"
    except Exception as e:
        logger.error(f"Health check DB falhou: {e}")
        db_status = "disconnected"
    
    return HealthResponse(
        status="ok",
        database=db_status,
        timestamp=datetime.now()
    )

@app.post("/verificar", response_model=VerificarResponse, tags=["Medicamentos"])
async def verificar_medicamento(
    request: VerificarRequest,
    pool: Pool = Depends(get_db_pool)
):
    """
    Endpoint principal: verifica medicamento via OCR da câmera.
    Recebe device_id + texto OCR, retorna dados + mensagem para TTS.
    """
    try:
        # 1. Definir contexto RLS para esta conexão
        await set_rls_context(pool, request.device_id)
        
        # 2. Preparar parâmetros
        data_validade = None
        if request.tem_validade and request.data_validade:
            try:
                data_validade = datetime.strptime(request.data_validade, "%Y-%m-%d").date()
            except ValueError:
                raise HTTPException(400, "Formato de data inválido. Use YYYY-MM-DD")
        
        # 3. Chamar função PL/pgSQL
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT * FROM verificar_medicamento_camera($1, $2, $3, $4)
                """,
                request.device_id,
                request.texto_ocr,
                request.tem_validade,
                data_validade
            )
        
        if not row:
            raise HTTPException(500, "Erro ao processar verificação")
        
        return VerificarResponse(**dict(row))
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Erro em /verificar: {e}", exc_info=True)
        raise HTTPException(500, "Erro interno ao processar verificação")

@app.post("/alergias", response_model=AlergiaResponse, tags=["Alergias"])
async def cadastrar_alergia(
    request: AlergiaRequest,
    pool: Pool = Depends(get_db_pool)
):
    """
    Cadastra alergia via voz (3 toques na tela).
    Recebe device_id + texto falado, resolve princípio ativo, criptografa e salva.
    """
    try:
        # 1. Definir contexto RLS
        await set_rls_context(pool, request.device_id)
        
        # 2. Chamar função PL/pgSQL
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT * FROM cadastrar_alergia_voz($1, $2, $3)
                """,
                request.device_id,
                request.texto_falado,
                request.origem
            )
        
        if not row:
            raise HTTPException(500, "Erro ao cadastrar alergia")
        
        return AlergiaResponse(**dict(row))
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Erro em /alergias POST: {e}", exc_info=True)
        raise HTTPException(500, "Erro interno ao cadastrar alergia")

@app.get("/alergias/{device_id}", response_model=AlergiaListResponse, tags=["Alergias"])
async def listar_alergias(
    device_id: str,
    pool: Pool = Depends(get_db_pool)
):
    """
    Lista alergias do usuário (descriptografadas para exibição).
    RLS garante que só vê as próprias.
    """
    try:
        await set_rls_context(pool, device_id)
        
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT 
                    id,
                    pgp_sym_decrypt(substancia_enc, current_setting('app.pgcrypto_key'))::text AS substancia,
                    origem,
                    criada_em
                FROM alergias_usuario
                ORDER BY criada_em DESC
                """
            )
        
        alergias = [dict(row) for row in rows]
        return AlergiaListResponse(device_id=device_id, alergias=alergias)
        
    except Exception as e:
        logger.error(f"Erro em /alergias GET: {e}", exc_info=True)
        raise HTTPException(500, "Erro ao listar alergias")

@app.post("/receitas", response_model=ReceitaResponse, tags=["Receitas"])
async def processar_receita(
    request: ReceitaRequest,
    pool: Pool = Depends(get_db_pool)
):
    """
    Processa receita médica via OCR (2 toques ou compartilhamento PDF/foto).
    Extrai medicamentos, horários, alergias e salva tudo.
    """
    try:
        await set_rls_context(pool, request.device_id)
        
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT * FROM processar_receita_ocr($1, $2)
                """,
                request.device_id,
                request.texto_ocr
            )
        
        if not row:
            raise HTTPException(500, "Erro ao processar receita")
        
        return ReceitaResponse(**dict(row))
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Erro em /receitas: {e}", exc_info=True)
        raise HTTPException(500, "Erro interno ao processar receita")

# ============================================================================
# EXECUÇÃO DIRETA (python main.py)
# ============================================================================

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.api_host,
        port=settings.api_port,
        workers=1,  # Use 1 worker em dev, múltiplos em produção via gunicorn
        reload=True,
        log_level=settings.log_level.lower()
    )