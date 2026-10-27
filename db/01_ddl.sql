-- ============================================================================
-- GuiaMed | 01_ddl.sql | Criação do banco, tabelas, índices e procedures
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
-- usuarios finais do bot (dados cadastrais minimos)
-- Classificacao: telegram_id/email = SENSIVEL (mascaramento dinamico); demais = INTERNO
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.usuario (
    id           bigint IDENTITY(1,1) PRIMARY KEY,
    telegram_id  nvarchar(32)  NOT NULL MASKED WITH (FUNCTION = 'partial(0, "***", 2)'),
    nome         nvarchar(120) NULL,     -- INTERNO
    email        nvarchar(256) NULL MASKED WITH (FUNCTION = 'email()'),  -- SENSIVEL
    papel        varchar(20)   NOT NULL DEFAULT 'paciente',  -- paciente | farmaceutico | suporte
    criado_em    datetime2     NOT NULL DEFAULT sysutcdatetime(),
    CONSTRAINT uq_usuario_telegram UNIQUE (telegram_id)
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
-- embedding preenchido depois por scripts/embed_chunks.py (OpenAI text-embedding-3-small)
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
-- trilha de auditoria das consultas (LGPD: contem texto do usuario)
-- Classificacao: pergunta/resposta = SENSIVEL (mascaramento + retencao 90 dias);
--                tokens/custo/modelo = INTERNO (metricas)
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.consulta_log (
    id              bigint IDENTITY(1,1) PRIMARY KEY,
    usuario_id      bigint NOT NULL REFERENCES dbo.usuario(id),
    canal           varchar(20) NOT NULL DEFAULT 'telegram',
    pergunta        nvarchar(max) NOT NULL MASKED WITH (FUNCTION = 'partial(10, " (...) ", 0)'), -- SENSIVEL
    resposta        nvarchar(max) NULL     MASKED WITH (FUNCTION = 'partial(10, " (...) ", 0)'), -- SENSIVEL
    scope_guard     varchar(20)  NULL,     -- in_scope | out_of_scope | injection_suspeita
    fonte_chunk_ids nvarchar(200) NULL,    -- ids dos chunks usados na resposta (trilha RAG)
    modelo          varchar(50)  NULL,
    tokens_in       int NULL,
    tokens_out      int NULL,
    custo_usd       decimal(9,6) NULL,
    criado_em       datetime2 NOT NULL DEFAULT sysutcdatetime()
);
CREATE INDEX ix_log_criado ON dbo.consulta_log (criado_em) INCLUDE (modelo, scope_guard);
GO

-- ---------------------------------------------------------------------------
-- feedback do usuario sobre a resposta
-- Classificacao: INTERNO (comentario SENSIVEL quando houver)
-- ---------------------------------------------------------------------------
CREATE TABLE dbo.feedback (
    id          bigint IDENTITY(1,1) PRIMARY KEY,
    consulta_id bigint NOT NULL REFERENCES dbo.consulta_log(id),
    nota        tinyint NOT NULL CHECK (nota BETWEEN 1 AND 5),
    comentario  nvarchar(500) NULL MASKED WITH (FUNCTION = 'default()'),
    criado_em   datetime2 NOT NULL DEFAULT sysutcdatetime()
);
GO

-- ---------------------------------------------------------------------------
-- Procedures: unico caminho de escrita/leitura para a IA (app_ia) e o n8n.
-- Consultas 100% parametrizadas (mitigacao de SQL Injection).
-- ---------------------------------------------------------------------------

-- Busca semantica top-N de chunks (RAG). Chamada pela procedure que o app usa.
CREATE OR ALTER PROCEDURE dbo.usp_buscar_bulas
    @consulta_embedding VECTOR(1536),
    @top_n int = 5,
    @medicamento nvarchar(120) = NULL   -- pre-filtro opcional (seletividade)
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

-- Busca exata por nome (usada pelo fluxo n8n antes do RAG; barata e parametrizada)
CREATE OR ALTER PROCEDURE dbo.usp_buscar_medicamento_nome
    @nome nvarchar(120)
AS
BEGIN
    SET NOCOUNT ON;
    SELECT id, nome_comercial, principio_ativo, classe_terapeutica, tarja,
           forma_farmaceutica, apresentacao
      FROM dbo.medicamento
     WHERE nome_comercial LIKE '%' + @nome + '%'
        OR principio_ativo LIKE '%' + @nome + '%'
     ORDER BY nome_comercial;
END
GO

-- Registro de auditoria. A IA so CONSEGUE escrever log via esta procedure
-- (ela nao tem INSERT/SELECT direto na tabela).
CREATE OR ALTER PROCEDURE dbo.usp_registrar_consulta
    @telegram_id     nvarchar(32),
    @pergunta        nvarchar(max),
    @resposta        nvarchar(max) = NULL,
    @scope_guard     varchar(20) = NULL,
    @fonte_chunk_ids nvarchar(200) = NULL,
    @modelo          varchar(50) = NULL,
    @tokens_in       int = NULL,
    @tokens_out      int = NULL,
    @custo_usd       decimal(9,6) = NULL,
    @canal           varchar(20) = 'telegram'
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @uid bigint;

    SELECT @uid = id FROM dbo.usuario WHERE telegram_id = @telegram_id;
    IF @uid IS NULL
    BEGIN
        INSERT INTO dbo.usuario (telegram_id) VALUES (@telegram_id);
        SET @uid = SCOPE_IDENTITY();
    END

    -- chave usada pela politica de RLS (cada sessao so ve o proprio log)
    EXEC sp_set_session_context N'usuario_id', @uid;

    INSERT INTO dbo.consulta_log
        (usuario_id, canal, pergunta, resposta, scope_guard, fonte_chunk_ids,
         modelo, tokens_in, tokens_out, custo_usd)
    VALUES
        (@uid, @canal, @pergunta, @resposta, @scope_guard, @fonte_chunk_ids,
         @modelo, @tokens_in, @tokens_out, @custo_usd);

    SELECT CAST(SCOPE_IDENTITY() AS bigint) AS consulta_id, @uid AS usuario_id;
END
GO
