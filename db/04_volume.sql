-- ============================================================================
-- GuiaMed | 04_volume.sql | Geracao de volume para o plano de otimizacao
-- Rodar DEPOIS de 01/02/03. Parametrizar @N conforme a maquina.
--
-- Gargalos alvo (docs/seguranca.md, secao Plano de otimizacao):
--   Q1 - busca vetorial top-5 em bula_chunk (scan + sort de distancias)
--   Q2 - relatorio de uso agregando consulta_log por dia/modelo (scan sequencial)
--
-- Volume alvo padrao: 1.000.000 de linhas em consulta_log
--                     50.000 vetores sinteticos em bula_chunk (~10-20 min)
-- ============================================================================
USE GuiaMed;
GO
SET NOCOUNT ON;

-- ---------------------------------------------------------------------------
-- 1) Massa em consulta_log (rapido: ~1M linhas em segundos/minutos)
-- ---------------------------------------------------------------------------
DECLARE @N bigint = 1000000;      -- ajuste o volume aqui
DECLARE @batch int = 20000;

TRUNCATE TABLE dbo.feedback;
DELETE FROM dbo.consulta_log;

IF NOT EXISTS (SELECT 1 FROM dbo.usuario)
    INSERT INTO dbo.usuario (telegram_id, nome)
    VALUES (N'9990001', N'Usuario Sintetico 1'), (N'9990002', N'Usuario Sintetico 2');

DECLARE @u1 bigint = (SELECT TOP 1 id FROM dbo.usuario ORDER BY id);
DECLARE @restantes bigint = @N;

WHILE @restantes > 0
BEGIN
    INSERT INTO dbo.consulta_log
        (usuario_id, canal, pergunta, resposta, scope_guard, modelo,
         tokens_in, tokens_out, custo_usd, criado_em)
    SELECT TOP (@batch)
           @u1 + (ABS(CHECKSUM(NEWID())) % 2),
           'telegram',
           CONCAT(N'qual a posologia do medicamento sintetico #', ABS(CHECKSUM(NEWID())) % 50000),
           N'Resposta ficticia gerada para teste de carga.',
           CASE ABS(CHECKSUM(NEWID())) % 20 WHEN 0 THEN 'out_of_scope' ELSE 'in_scope' END,
           CASE ABS(CHECKSUM(NEWID())) % 3 WHEN 0 THEN 'gpt-4o-mini-ft' ELSE 'gpt-4o-mini' END,
           1200 + ABS(CHECKSUM(NEWID())) % 800,
           150  + ABS(CHECKSUM(NEWID())) % 300,
           CAST(0.0003 AS decimal(9,6)),
           DATEADD(second, -ABS(CHECKSUM(NEWID())) % 7776000, sysutcdatetime()) -- ultimos 90 dias
      FROM sys.all_objects a CROSS JOIN sys.all_objects b;

    SET @restantes -= @batch;
END
GO

-- ---------------------------------------------------------------------------
-- 2) Vetores sinteticos em bula_chunk (pesado: gerar em horario ocioso)
--    Replica os 144 chunks reais com sufixo de versao e embedding aleatorio
--    normalizado no formato JSON aceito por VECTOR(1536).
-- ---------------------------------------------------------------------------
DECLARE @vetores_alvo int = 50000;   -- ajuste aqui
DECLARE @lote int = 500;
DECLARE @feitos int = 0;
DECLARE @template nvarchar(max);

WHILE @feitos < @vetores_alvo
BEGIN
    -- monta UM embedding aleatorio por iteracao do lote e replica em 500 chunks
    WITH nums AS (
        SELECT TOP (1536) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
        FROM sys.all_objects
    )
    SELECT @template = '[' + STRING_AGG(CAST(CAST(RAND(CHECKSUM(NEWID())) AS real) AS varchar(20)), ',') + ']'
    FROM nums;

    INSERT INTO dbo.bula_chunk (medicamento_id, secao, conteudo, embedding)
    SELECT TOP (@lote)
           (ABS(CHECKSUM(NEWID())) % 24) + 1,
           'indicacao',
           CONCAT(N'Chunk sintetico de volume #', ABS(CHECKSUM(NEWID())) % 1000000,
                  N'. Conteudo ficticio apenas para teste de desempenho da busca vetorial.'),
           CAST(@template AS VECTOR(1536))
      FROM sys.all_objects;

    SET @feitos += @lote;
    IF @feitos % 5000 = 0 PRINT CONCAT('... ', @feitos, ' vetores inseridos');
END
GO

-- ---------------------------------------------------------------------------
-- 3) Queries de benchmark (rodar com SET STATISTICS IO, TIME ON antes/depois)
-- ---------------------------------------------------------------------------
-- Q1 - busca vetorial (substituir @q por um embedding real ou sintetico):
--   DECLARE @q VECTOR(1536) = (SELECT TOP 1 embedding FROM dbo.bula_chunk
--                              WHERE embedding IS NOT NULL);
--   EXEC dbo.usp_buscar_bulas @q, 5;
--
-- Q2 - relatorio de uso por dia/modelo:
--   SELECT CAST(criado_em AS date) AS dia, modelo, COUNT_BIG(*) AS qtd,
--          SUM(tokens_in) AS tokens_in, SUM(tokens_out) AS tokens_out,
--          SUM(custo_usd) AS custo
--     FROM dbo.consulta_log
--    GROUP BY CAST(criado_em AS date), modelo
--    ORDER BY dia;
--
-- Otimizacoes planejadas (aplicar e medir de novo):
--   Q1: pre-filtro por medicamento (WHERE medicamento_id = X) + indice vetorial:
--       CREATE VECTOR INDEX ix_chunk_vec ON bula_chunk(embedding)
--       WITH (METRIC = 'cosine', TYPE = 'DiskANN');
--   Q2: indice columnstore para agregacao analitica:
--       CREATE NONCLUSTERED COLUMNSTORE INDEX ix_log_cs
--       ON dbo.consulta_log (criado_em, modelo, tokens_in, tokens_out, custo_usd);
-- ============================================================================
