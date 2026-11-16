-- GuiaMed | Evidência reproduzível: permissões da IA/API e auditoria
-- Pré-requisito: executar 01_ddl.sql, 03_seed.sql, 05_seed_app.sql e 02_security.sql.
-- Execute os blocos A e B em uma sessão autenticada como svc_guiamed_ia.

USE GuiaMed;
GO

-- A. Este acesso direto DEVE falhar: rl_app_ia possui DENY no schema dbo.
SELECT * FROM dbo.alergia;
GO

-- B. A IA/API pode usar a procedure com o mínimo necessário.
EXEC dbo.usp_checar_alergia
    @device_id = N'dev-demo-0001',
    @principio_ativo = N'reumexato',
    @nome_comercial = N'Reumaxil';
GO

-- C. A IA/API também registra sua consulta por procedure, sem INSERT direto.
EXEC dbo.usp_registrar_consulta
    @device_id = N'dev-demo-0001',
    @entrada_ocr = N'Reumaxil 20 mg',
    @resposta_voz = N'Alerta de alergia fictício.',
    @alerta_alergia = 1,
    @extracao_json = N'{"nome":"Reumaxil"}',
    @fonte_chunk_ids = N'1,2',
    @modelo = N'ocr-local',
    @canal = 'app_voz';
GO

-- D. Executar como DBA para capturar o print da trilha criada.
SELECT TOP (1) dispositivo_id, canal, alerta_alergia, extracao_json,
       fonte_chunk_ids, modelo, criado_em
FROM dbo.consulta_log
ORDER BY id DESC;
GO
