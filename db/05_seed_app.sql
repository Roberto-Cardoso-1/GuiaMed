-- ============================================================================
-- GuiaMed (app de acessibilidade) | 05_seed_app.sql
-- Dados DEMO do app: 1 dispositivo, 3 alergias e 1 receita (TUDO FICTICIO)
-- Rodar DEPOIS de 03_seed.sql (precisa dos medicamentos) e ANTES de 02_security.sql
-- ============================================================================
USE GuiaMed;
GO
SET NOCOUNT ON;

-- Dispositivo demo (identidade anonima - sem nome, email ou login)
IF NOT EXISTS (SELECT 1 FROM dbo.dispositivo WHERE device_id = N'dev-demo-0001')
    INSERT INTO dbo.dispositivo (device_id, apelido)
    VALUES (N'dev-demo-0001', N'Usuária demo');
GO
DECLARE @demo bigint = (SELECT id FROM dbo.dispositivo WHERE device_id = N'dev-demo-0001');

-- 3 alergias ficticias da usuaria demo (resolve por principio ativo da base):
--  - paraxetamol   -> Dorfanol
--  - bronferol     -> Asmaplus
--  - reumexato     -> Reumaxil   (posologia semanal critica: alerta perfeito p/ demo)
IF NOT EXISTS (SELECT 1 FROM dbo.alergia WHERE dispositivo_id = @demo)
BEGIN
    INSERT INTO dbo.alergia (dispositivo_id, substancia, origem)
    SELECT @demo, m.principio_ativo, 'voz'
      FROM dbo.medicamento m
     WHERE m.nome_comercial IN (N'Dorfanol', N'Asmaplus', N'Reumaxil');
END
GO

-- Receita FICTICIA digitalizada (texto como sairia do OCR da camera)
DECLARE @demo bigint = (SELECT id FROM dbo.dispositivo WHERE device_id = N'dev-demo-0001');
IF NOT EXISTS (SELECT 1 FROM dbo.receita WHERE dispositivo_id = @demo)
    INSERT INTO dbo.receita (dispositivo_id, texto_ocr, medicamentos_json)
    VALUES (@demo,
N'Dr(a). Exemplo Ficticio CRM-XX 00000
Paciente: Usuaria Demo
1) Pressormin 10 mg --- 1 comp. pela manha --- 30 dias
2) Tireonorm 50 mcg --- 1 comp. em jejum --- uso continuo
3) Ventacid susp. --- 10 mL apos refeicoes se azia
Retorno em 90 dias. Data: __/__/____ Assinatura: __________',
N'[{"nome_comercial":"Pressormin","principio_ativo":"losaprilato de sódio"},{"nome_comercial":"Tireonorm","principio_ativo":"levotironato de sódio"},{"nome_comercial":"Ventacid","principio_ativo":"hidróxido de magnidril + simeticona"}]');
GO

PRINT 'Demo pronta: dev-demo-0001 com 3 alergias e 1 receita (teste: EXEC dbo.usp_checar_alergia N''dev-demo-0001'', N''reumexato'', N''Reumaxil'')';
GO
