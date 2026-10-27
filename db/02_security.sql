-- ============================================================================
-- GuiaMed (app de acessibilidade) | 02_security.sql
-- Papeis, GRANT/DENY, RLS, mascara e criptografia
-- Rodar DEPOIS de 01_ddl.sql, 03_seed.sql e 05_seed_app.sql
-- ============================================================================
USE GuiaMed;
GO

-- ---------------------------------------------------------------------------
-- Papeis (matriz de papeis detalhada em docs/seguranca.md)
-- ---------------------------------------------------------------------------
CREATE ROLE rl_paciente;       -- usuario do app (via identidade anonima)
CREATE ROLE rl_farmaceutico;   -- curador das bulas
CREATE ROLE rl_suporte;        -- operacao: le logs mascarados, nao escreve
CREATE ROLE rl_app_ia;         -- identidade da API + IA/n8n (privilegio minimo)
GO

-- Login/usuario de servico usado pela API e pelo n8n (a "IA" vista pelo banco)
-- SENHA NAO FICA NO REPOSITORIO: passe por fora na hora de rodar:
--   sqlcmd -v IA_PWD="SuaSenhaForte!123" -i db/02_security.sql
-- (SSMS/Azure Data Studio: habilite o modo SQLCMD, ou troque $(IA_PWD) manualmente.)
CREATE LOGIN svc_guiamed_ia WITH PASSWORD = N'$(IA_PWD)';
CREATE USER app_ia FOR LOGIN svc_guiamed_ia;
ALTER ROLE rl_app_ia ADD MEMBER app_ia;
GO

-- ---------------------------------------------------------------------------
-- PACIENTE: uso real e via app (que entra como app_ia). Em acesso direto (BI),
-- ve o catalogo publico e APENAS os proprios dados (RLS). Nao altera nada.
-- ---------------------------------------------------------------------------
GRANT SELECT ON dbo.medicamento    TO rl_paciente;
GRANT SELECT ON dbo.bula_chunk     TO rl_paciente;
GRANT SELECT ON dbo.alergia        TO rl_paciente;   -- restringido pela RLS abaixo
GRANT SELECT ON dbo.receita        TO rl_paciente;   -- restringido pela RLS abaixo
GRANT SELECT ON dbo.consulta_log   TO rl_paciente;   -- restringido pela RLS abaixo
DENY  INSERT, UPDATE, DELETE ON dbo.dispositivo  TO rl_paciente;
DENY  INSERT, UPDATE, DELETE ON dbo.alergia      TO rl_paciente;
DENY  INSERT, UPDATE, DELETE ON dbo.receita      TO rl_paciente;
DENY  INSERT, UPDATE, DELETE ON dbo.consulta_log TO rl_paciente;
GO

-- ---------------------------------------------------------------------------
-- FARMACEUTICO: curador do catalogo; faz estatistica em dados MASCARADOS.
-- NAO ve identidade (device_id) e NAO ve receitas.
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON dbo.medicamento TO rl_farmaceutico;
GRANT SELECT, INSERT, UPDATE, DELETE ON dbo.bula_chunk TO rl_farmaceutico;
GRANT SELECT ON dbo.consulta_log TO rl_farmaceutico; -- chega mascarado (DDM)
GRANT SELECT ON dbo.alergia      TO rl_farmaceutico; -- substancia mascarada (DDM)
GRANT SELECT ON dbo.dispositivo  TO rl_farmaceutico;
DENY  SELECT ON dbo.dispositivo (device_id) TO rl_farmaceutico;  -- DENY em coluna sensivel
DENY  SELECT ON dbo.receita  TO rl_farmaceutico;                 -- DENY em tabela sensivel (saude)
DENY  UPDATE, DELETE ON dbo.consulta_log TO rl_farmaceutico;     -- log e integro/inalteravel
GO

-- ---------------------------------------------------------------------------
-- SUPORTE: leitura operacional de metricas. NAO ve dados de saude nem escreve.
-- ---------------------------------------------------------------------------
GRANT SELECT ON dbo.consulta_log TO rl_suporte;  -- mascarado (DDM)
DENY  SELECT, INSERT, UPDATE, DELETE ON dbo.alergia      TO rl_suporte;
DENY  SELECT, INSERT, UPDATE, DELETE ON dbo.receita      TO rl_suporte;
DENY  SELECT, INSERT, UPDATE, DELETE ON dbo.dispositivo  TO rl_suporte;
DENY  INSERT, UPDATE, DELETE        ON dbo.medicamento   TO rl_suporte;
DENY  INSERT, UPDATE, DELETE, ALTER ON dbo.bula_chunk    TO rl_suporte;
GO

-- ---------------------------------------------------------------------------
-- APP_IA (API/n8n/LLM): privilegio minimo absoluto. So executa procedures;
-- nao le tabelas diretamente - nem as publicas. As procedures definem o que
-- a IA pode ver e fazer (ver docs/seguranca.md, matriz ampliada).
-- ---------------------------------------------------------------------------
GRANT EXECUTE ON dbo.usp_obter_ou_criar_dispositivo TO rl_app_ia;
GRANT EXECUTE ON dbo.usp_registrar_alergia          TO rl_app_ia;
GRANT EXECUTE ON dbo.usp_checar_alergia             TO rl_app_ia;
GRANT EXECUTE ON dbo.usp_registrar_receita          TO rl_app_ia;
GRANT EXECUTE ON dbo.usp_buscar_bulas               TO rl_app_ia;
GRANT EXECUTE ON dbo.usp_registrar_consulta         TO rl_app_ia;
DENY  SELECT, INSERT, UPDATE, DELETE ON SCHEMA::dbo TO rl_app_ia;  -- fecha todas as tabelas
DENY  ALTER, CONTROL, TAKE OWNERSHIP ON SCHEMA::dbo TO rl_app_ia;  -- anti-escalonamento
GO

-- ---------------------------------------------------------------------------
-- Row-Level Security: dados sensiveis so visiveis para o proprio dispositivo
-- (contexto fixado em usp_obter_ou_criar_dispositivo) ou papeis autorizados.
-- ---------------------------------------------------------------------------

-- alergia e consulta_log: dono + farmaceutico + suporte(no log) + DBA
CREATE OR ALTER FUNCTION dbo.fn_rls_app (@dispositivo_id bigint)
RETURNS TABLE WITH SCHEMABINDING
AS
RETURN
    SELECT 1 AS permitido
    WHERE @dispositivo_id = TRY_CAST(SESSION_CONTEXT(N'dispositivo_id') AS bigint)  -- dono
       OR IS_MEMBER('rl_farmaceutico') = 1
       OR IS_MEMBER('rl_suporte') = 1
       OR IS_MEMBER('db_owner') = 1;
GO

CREATE SECURITY POLICY dbo.pol_rls_alergia
    ADD FILTER PREDICATE dbo.fn_rls_app(dispositivo_id) ON dbo.alergia
    WITH (STATE = ON, SCHEMABINDING = ON);
GO
CREATE SECURITY POLICY dbo.pol_rls_consulta
    ADD FILTER PREDICATE dbo.fn_rls_app(dispositivo_id) ON dbo.consulta_log
    WITH (STATE = ON, SCHEMABINDING = ON);
GO

-- receita (saude): SOMENTE o dono (+DBA). Nem farmaceutico nem suporte.
CREATE OR ALTER FUNCTION dbo.fn_rls_proprio (@dispositivo_id bigint)
RETURNS TABLE WITH SCHEMABINDING
AS
RETURN
    SELECT 1 AS permitido
    WHERE @dispositivo_id = TRY_CAST(SESSION_CONTEXT(N'dispositivo_id') AS bigint)
       OR IS_MEMBER('db_owner') = 1;
GO

CREATE SECURITY POLICY dbo.pol_rls_receita
    ADD FILTER PREDICATE dbo.fn_rls_proprio(dispositivo_id) ON dbo.receita
    WITH (STATE = ON, SCHEMABINDING = ON);
GO

-- ---------------------------------------------------------------------------
-- Criptografia em repouso (TDE). No Azure SQL DB ja vem ligado por padrao.
-- Local (SQL Server 2025): descomentar e executar como sysadmin:
-- ---------------------------------------------------------------------------
-- USE master;
-- CREATE MASTER KEY ENCRYPTION BY PASSWORD = '<sua-senha-forte-aqui>';
-- CREATE CERTIFICATE GuiaMedTdeCert WITH SUBJECT = 'GuiaMed TDE';
-- USE GuiaMed;
-- CREATE DATABASE ENCRYPTION KEY WITH ALGORITHM = AES_256
--     ENCRYPTION BY SERVER CERTIFICATE GuiaMedTdeCert;
-- ALTER DATABASE GuiaMed SET ENCRYPTION ON;
-- GO

-- ---------------------------------------------------------------------------
-- Auditoria: toda interacao ja e registrada em dbo.consulta_log pela
-- usp_registrar_consulta (trilha de aplicacao). Opcional extra: SERVER AUDIT
-- para registrar logins e comandos DDL (anti-escalonamento).
-- ---------------------------------------------------------------------------
-- CREATE SERVER AUDIT audit_guiamed TO FILE (FILEPATH = 'C:\auditoria\');
-- ALTER SERVER AUDIT audit_guiamed WITH (STATE = ON);
GO
