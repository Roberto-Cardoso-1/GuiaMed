-- ============================================================================
-- GuiaMed (app de acessibilidade) | 04_volume.sql
-- Geracao de massa para o plano de otimizacao (ver docs/seguranca.md §4)
--   - ~1.000.000 linhas em consulta_log (batches de 20k)
--   - 50.000 vetores sinteticos em bula_chunk
-- Rodar DEPOIS de 01_ddl.sql e 03_seed.sql. Demora alguns minutos.
-- ============================================================================
USE GuiaMed;
GO
SET NOCOUNT ON;

-- ---------------------------------------------------------------------------
-- 0) Dispositivos sinteticos p/ dar cardinalidade ao log (1.000 aparelhos)
-- ---------------------------------------------------------------------------
;WITH t AS (
    SELECT TOP (1000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
    FROM (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) a(x)
    CROSS JOIN (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) b(x)
    CROSS JOIN (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) c(x)
)
INSERT INTO dbo.dispositivo (device_id)
SELECT N'vol_dev_' + RIGHT(N'0000' + CAST(n AS varchar(5)), 5)
  FROM t
 WHERE NOT EXISTS (SELECT 1 FROM dbo.dispositivo d WHERE d.device_id = N'vol_dev_' + RIGHT(N'0000' + CAST(n AS varchar(5)), 5));
GO

-- ---------------------------------------------------------------------------
-- 1) 1.000.000 de linhas em consulta_log (50 batches x 20.000)
--    distribuidas em 90 dias, ~3% com alerta de alergia, 3 modelos
-- ---------------------------------------------------------------------------
DECLARE @batch int = 0;
WHILE @batch < 50
BEGIN
    ;WITH t AS (
        SELECT TOP (20000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
        FROM (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) a(x)
        CROSS JOIN (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) b(x)
        CROSS JOIN (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) c(x)
        CROSS JOIN (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) d(x)
        CROSS JOIN (VALUES (0),(0)) e(x)
    )
    INSERT INTO dbo.consulta_log
        (dispositivo_id, canal, entrada_ocr, resposta_voz, alerta_alergia,
         extracao_json, modelo, tokens_in, tokens_out, custo_usd, criado_em)
    SELECT
        1 + ABS(CHECKSUM(NEWID())) % 1000,
        'app_voz',
        N'texto ocr sintetico volume ' + CAST(n AS varchar(10)),
        N'resposta falada sintetica volume ' + CAST(n AS varchar(10)),
        CASE WHEN ABS(CHECKSUM(NEWID())) % 100 < 3 THEN 1 ELSE 0 END,
        N'{"nome_comercial":"Pressormin","principio_ativo":"losaprilato de sódio","validade":null}',
        CASE ABS(CHECKSUM(NEWID())) % 3
             WHEN 0 THEN 'gpt-4o-mini'
             WHEN 1 THEN 'ft:gpt-4o-mini:ocr-extractor'
             ELSE 'text-embedding-3-small' END,
        100 + ABS(CHECKSUM(NEWID())) % 800,
        50  + ABS(CHECKSUM(NEWID())) % 200,
        CAST((ABS(CHECKSUM(NEWID())) % 100) / 1000000.0 AS decimal(9,6)),
        DATEADD(minute, -(ABS(CHECKSUM(NEWID())) % 129600), sysutcdatetime())
    FROM t;

    SET @batch += 1;
    PRINT 'consulta_log: batch ' + CAST(@batch AS varchar(3)) + '/50';
END
GO

-- ---------------------------------------------------------------------------
-- 2) 50.000 vetores sinteticos (VECTOR(1536) valido, padrao com jitter por linha)
--    Serve para medir o full scan da busca vetorial (Q1), nao a semantica.
-- ---------------------------------------------------------------------------
DECLARE @vbase nvarchar(max) = N'[' + REPLICATE(N'0.11,0.23,0.37,0.41,0.53,0.67,0.71,0.83,', 191) + N'0.83]';

;WITH t AS (
    SELECT TOP (50000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
    FROM (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) a(x)
    CROSS JOIN (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) b(x)
    CROSS JOIN (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) c(x)
    CROSS JOIN (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) d(x)
    CROSS JOIN (VALUES (0),(0),(0),(0),(0)) e(x)
)
INSERT INTO dbo.bula_chunk (medicamento_id, secao, conteudo, embedding)
SELECT 1 + (n % 24), 'indicacao',
       N'chunk sintetico de volume ' + CAST(n AS varchar(10)),
       CAST(REPLACE(@vbase, '0.11', CAST(0.05 + (n % 20) / 100.0 AS varchar(10))) AS VECTOR(1536))
  FROM t;
GO

PRINT 'Volume gerado: ~1.000.000 logs e ~50.000 vetores. Rode o plano de otimizacao (docs/seguranca.md §4).';
GO
