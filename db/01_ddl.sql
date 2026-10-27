-- ============================================================================
-- GuiaMed (app de acessibilidade por voz) | 01_ddl.sql
-- Criação do banco, tabelas, índices e procedures
-- Alvo: SQL Server 2025 (tipo VECTOR nativo) local em Docker ou Azure SQL DB
-- ============================================================================
-- Subir SQL Server 2025 local (Docker):
--   docker run -e "ACCEPT_EULA=Y" -e "MSSQL_SA_PASSWORD=<DefinaUmaSenhaForte!Aqui>" `
--     -p 1433:1433 --name guiamed-sql mcr.microsoft.com/mssql/server:2025-latest
-- ============================================================================

IF DB_ID('GuiaMed') IS NULL
    CREATE DATABASE GuiaMed;
GO
USE GuiaMed;
GO

-- ---------------------------------------------------------------------------
-- dispositivos (identidade anonima do usuario do app - SEM login/senha)
-- Classificacao: device_id = SENSIVEL (mascaramento dinamico); demais = INTERNO
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.dispositivo (
    id          bigint IDENTITY(1,1) PRIMARY KEY,
    device_id   nvarchar(64)  NOT NULL MASKED WITH (FUNCTION = 'partial(0, "***", 4)'),  -- SENSIVEL
    apelido     nvarchar(60)  NULL,     -- INTERNO (apelido falado pelo usuario, opcional)
    criado_em   datetime2     NOT NULL DEFAULT sysutcdatetime(),
    CONSTRAINT uq_dispositivo_device UNIQUE (device_id)
);
GO

-- ---------------------------------------------------------------------------
-- medicamentos cadastrados (base publica de bulas ficticias)
-- Classificacao: todas PUBLICO
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.medicamento (
    id                  int IDENTITY(1,1) PRIMARY KEY,
    nome_comercial      nvarchar(120) NOT NULL UNIQUE,
    principio_ativo     nvarchar(160) NOT NULL,
    classe_terapeutica  nvarchar(160) NOT NULL,
    tarja               varchar(10)   NOT NULL CHECK (tarja IN ('isenta','vermelha','amarela')),
    forma_farmaceutica  nvarchar(120) NOT NULL,
    apresentacao        nvarchar(160) NOT NULL,
    criado_em           datetime2 NOT NULL DEFAULT sysutcdatetime()
);
GO

-- ---------------------------------------------------------------------------
-- chunks das bulas + embeddings (RAG)
-- Chunking: 1 chunk = 1 secao da bula (indicacao, posologia, ...).
-- Classificacao: conteudo PUBLICO (bula), embedding INTERNO (artefato do sistema)
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.bula_chunk (
    id             bigint IDENTITY(1,1) PRIMARY KEY,
    medicamento_id int NOT NULL REFERENCES dbo.medicamento(id),
    secao          varchar(30) NOT NULL CHECK (secao IN
                     ('indicacao','posologia','contraindicacoes','efeitos_adversos','interacoes','advertencias')),
    conteudo       nvarchar(max) NOT NULL,
    embedding      VECTOR(1536) NULL,          -- SQL Server 2025
    criado_em      datetime2 NOT NULL DEFAULT sysutcdatetime()
);
CREATE INDEX ix_chunk_medicamento ON dbo.bula_chunk (medicamento_id, secao);
GO

-- ---------------------------------------------------------------------------
-- alergias do usuario (DADO DE SAUDE - LGPD art. 11)
-- cadastradas por voz ou extraidas de receita
-- Classificacao: substancia = SENSIVEL (mascaramento + RLS); demais = INTERNO
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.alergia (
    id             bigint IDENTITY(1,1) PRIMARY KEY,
    dispositivo_id bigint NOT NULL REFERENCES dbo.dispositivo(id),
    substancia     nvarchar(160) NOT NULL MASKED WITH (FUNCTION = 'default()'),  -- SENSIVEL (saude)
    origem         varchar(10)   NOT NULL DEFAULT 'voz' CHECK (origem IN ('voz','receita','manual')),
    criada_em      datetime2     NOT NULL DEFAULT sysutcdatetime()
);
CREATE INDEX ix_alergia_dispositivo ON dbo.alergia (dispositivo_id);
GO

-- ---------------------------------------------------------------------------
-- receitas digitalizadas (foto do papel -> OCR)
-- Classificacao: texto_ocr e medicamentos_json = SENSIVEL (saude); demais INTERNO
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.receita (
    id                bigint IDENTITY(1,1) PRIMARY KEY,
    dispositivo_id    bigint NOT NULL REFERENCES dbo.dispositivo(id),
    texto_ocr         nvarchar(max) NOT NULL MASKED WITH (FUNCTION = 'partial(10, " (...) ", 0)'),  -- SENSIVEL
    medicamentos_json nvarchar(max) NULL     MASKED WITH (FUNCTION = 'default()'),                 -- SENSIVEL
    criada_em         datetime2     NOT NULL DEFAULT sysutcdatetime()
);
CREATE INDEX ix_receita_dispositivo ON dbo.receita (dispositivo_id);
GO

-- ---------------------------------------------------------------------------
-- trilha de auditoria das interacoes (foto/fala -> resposta falada)
-- Classificacao: entrada_ocr/resposta_voz = SENSIVEL (mascaramento + retencao 90d);
--                tokens/custo/modelo = INTERNO (metricas)
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.consulta_log (
    id              bigint IDENTITY(1,1) PRIMARY KEY,
    dispositivo_id  bigint NOT NULL REFERENCES dbo.dispositivo(id),
    canal           varchar(20) NOT NULL DEFAULT 'app_voz',
    entrada_ocr     nvarchar(max) NOT NULL MASKED WITH (FUNCTION = 'partial(10, " (...) ", 0)'), -- SENSIVEL
    resposta_voz    nvarchar(max) NULL     MASKED WITH (FUNCTION = 'partial(10, " (...) ", 0)'), -- SENSIVEL
    alerta_alergia  bit           NULL,    -- 1 se a resposta foi alerta de conflito com alergia
    extracao_json   nvarchar(500) NULL,    -- saida do extrator fine-tuned (trilha IA)
    fonte_chunk_ids nvarchar(200) NULL,    -- ids dos chunks usados na resposta (trilha RAG)
    modelo          varchar(50)  NULL,
    tokens_in       int NULL,
    tokens_out      int NULL,
    custo_usd       decimal(9,6) NULL,
    criado_em       datetime2   NOT NULL DEFAULT sysutcdatetime()
);
CREATE INDEX ix_log_criado ON dbo.consulta_log (criado_em) INCLUDE (modelo, alerta_alergia);
GO

-- ---------------------------------------------------------------------------
-- Procedures: unico caminho de leitura/escrita para a API/IA (app_ia) e o n8n.
-- 100% parametrizadas (mitigacao de SQL Injection).
-- ---------------------------------------------------------------------------

-- Identidade anonima: obtem (ou cria) o dispositivo e fixa o contexto de RLS.
-- Retorna o id por OUTPUT (uso interno) e por resultset (uso pela API/n8n).
CREATE OR ALTER PROCEDURE dbo.usp_obter_ou_criar_dispositivo
    @device_id nvarchar(64),
    @did       bigint OUTPUT
AS
BEGIN
    SET NOCOUNT ON;

    SELECT @did = id FROM dbo.dispositivo WHERE device_id = @device_id;
    IF @did IS NULL
    BEGIN
        INSERT INTO dbo.dispositivo (device_id) VALUES (@device_id);
        SET @did = SCOPE_IDENTITY();
    END

    -- chave usada pelas politicas de RLS (cada sessao so ve os proprios dados)
    EXEC sp_set_session_context N'dispositivo_id', @did;

    SELECT @did AS dispositivo_id;
END
GO

-- Cadastra alergia a partir da fala do usuario (ex.: "sou alergico a dipirona").
-- Tenta resolver para o principio ativo de um medicamento cadastrado; senao,
-- guarda o texto falado como esta (curadoria do farmaceutico depois).
CREATE OR ALTER PROCEDURE dbo.usp_registrar_alergia
    @device_id     nvarchar(64),
    @texto_falado  nvarchar(300),
    @origem        varchar(10) = 'voz'
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @did bigint, @substancia nvarchar(160);

    EXEC dbo.usp_obter_ou_criar_dispositivo @device_id, @did OUTPUT;

    SELECT TOP 1 @substancia = m.principio_ativo
      FROM dbo.medicamento m
     WHERE @texto_falado LIKE '%' + m.nome_comercial + '%'
        OR @texto_falado LIKE '%' + m.principio_ativo + '%';
    IF @substancia IS NULL SET @substancia = @texto_falado;

    IF NOT EXISTS (SELECT 1 FROM dbo.alergia
                    WHERE dispositivo_id = @did AND substancia = @substancia)
        INSERT INTO dbo.alergia (dispositivo_id, substancia, origem)
        VALUES (@did, @substancia, @origem);

    SELECT @substancia AS substancia_registrada;
END
GO

-- Checagem de alergia: o remedio identificado na camera conflita com as
-- alergias do dispositivo? Caminho UNICO pelo qual a IA "ve" dados de saude.
CREATE OR ALTER PROCEDURE dbo.usp_checar_alergia
    @device_id        nvarchar(64),
    @principio_ativo  nvarchar(160),
    @nome_comercial   nvarchar(120) = NULL
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @did bigint, @substancia nvarchar(160);

    EXEC dbo.usp_obter_ou_criar_dispositivo @device_id, @did OUTPUT;

    SELECT TOP 1 @substancia = a.substancia
      FROM dbo.alergia a
      JOIN dbo.medicamento m
        ON a.substancia = m.principio_ativo OR a.substancia = m.nome_comercial
     WHERE a.dispositivo_id = @did
       AND (m.principio_ativo = @principio_ativo OR m.nome_comercial = @nome_comercial);

    -- cobre tambem alergia registrada em texto livre que mencione o principio ativo
    IF @substancia IS NULL
        SELECT TOP 1 @substancia = a.substancia
          FROM dbo.alergia a
         WHERE a.dispositivo_id = @did
           AND (@principio_ativo LIKE '%' + a.substancia + '%'
                OR a.substancia LIKE '%' + @principio_ativo + '%');

    SELECT CAST(CASE WHEN @substancia IS NULL THEN 0 ELSE 1 END AS bit) AS conflito,
           @substancia AS substancia_em_conflito;
END
GO

-- Guarda a receita digitalizada (texto OCR bruto + JSON extraido pela IA).
CREATE OR ALTER PROCEDURE dbo.usp_registrar_receita
    @device_id         nvarchar(64),
    @texto_ocr         nvarchar(max),
    @medicamentos_json nvarchar(max) = NULL
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @did bigint;
    EXEC dbo.usp_obter_ou_criar_dispositivo @device_id, @did OUTPUT;

    INSERT INTO dbo.receita (dispositivo_id, texto_ocr, medicamentos_json)
    VALUES (@did, @texto_ocr, @medicamentos_json);

    SELECT CAST(SCOPE_IDENTITY() AS bigint) AS receita_id;
END
GO

-- Busca semantica top-N de chunks (RAG). Pre-filtro por medicamento quando o
-- extrator identificou o nome com confianca (voz exige precisao).
CREATE OR ALTER PROCEDURE dbo.usp_buscar_bulas
    @consulta_embedding VECTOR(1536),
    @top_n int = 5,
    @medicamento nvarchar(120) = NULL
AS
BEGIN
    SET NOCOUNT ON;
    SELECT TOP (@top_n)
           c.id, c.medicamento_id, m.nome_comercial, c.secao, c.conteudo,
           VECTOR_DISTANCE('cosine', @consulta_embedding, c.embedding) AS distancia
      FROM dbo.bula_chunk c
      JOIN dbo.medicamento m ON m.id = c.medicamento_id
     WHERE (@medicamento IS NULL OR m.nome_comercial = @medicamento)
       AND c.embedding IS NOT NULL
     ORDER BY distancia;
END
GO

-- Registro de auditoria. A IA so CONSEGUE escrever log via esta procedure
-- (ela nao tem INSERT/SELECT direto na tabela).
CREATE OR ALTER PROCEDURE dbo.usp_registrar_consulta
    @device_id       nvarchar(64),
    @entrada_ocr     nvarchar(max),
    @resposta_voz    nvarchar(max) = NULL,
    @alerta_alergia  bit = NULL,
    @extracao_json   nvarchar(500) = NULL,
    @fonte_chunk_ids nvarchar(200) = NULL,
    @modelo          varchar(50)  = NULL,
    @tokens_in       int = NULL,
    @tokens_out      int = NULL,
    @custo_usd       decimal(9,6) = NULL,
    @canal           varchar(20)  = 'app_voz'
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @did bigint;
    EXEC dbo.usp_obter_ou_criar_dispositivo @device_id, @did OUTPUT;

    INSERT INTO dbo.consulta_log
        (dispositivo_id, canal, entrada_ocr, resposta_voz, alerta_alergia,
         extracao_json, fonte_chunk_ids, modelo, tokens_in, tokens_out, custo_usd)
    VALUES
        (@did, @canal, @entrada_ocr, @resposta_voz, @alerta_alergia,
         @extracao_json, @fonte_chunk_ids, @modelo, @tokens_in, @tokens_out, @custo_usd);

    SELECT CAST(SCOPE_IDENTITY() AS bigint) AS consulta_id, @did AS dispositivo_id;
END
GO
