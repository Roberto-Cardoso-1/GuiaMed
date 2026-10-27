-- ============================================================================
-- GuiaMed | 02_security.sql | Papeis, GRANT/DENY, RLS, mascara e criptografia
-- Rodar DEPOIS de 01_ddl.sql e 03_seed.sql
-- ============================================================================
USE GuiaMed;
GO

-- ---------------------------------------------------------------------------
-- Papeis (matriz de papeis detalhada em docs/seguranca.md)
-- ---------------------------------------------------------------------------
CREATE ROLE rl_paciente;       -- usuario final do bot (via app)
CREATE ROLE rl_farmaceutico;   -- curador das bulas
CREATE ROLE rl_suporte;        -- operacao/suporte: le logs mascarados, nao escreve
CREATE ROLE rl_app_ia;         -- identidade do fluxo n8n/IA (privilegio minimo)
GO

-- Login/usuario de servico usado pelo n8n (a "IA" vista pelo banco)
-- SENHA NAO FICA NO REPOSITORIO: passe por fora na hora de rodar:
--   sqlcmd -v IA_PWD="SuaSenhaForte!123" -i db/02_security.sql
-- (SSMS/Azure Data Studio: habilite o modo SQLCMD, ou troque $(IA_PWD) manualmente.)
CREATE LOGIN svc_guiamed_ia WITH PASSWORD = N'$(IA_PWD)';
CREATE USER app_ia FOR LOGIN svc_guiamed_ia;
ALTER ROLE rl_app_ia ADD MEMBER app_ia;
GO

-- ---------------------------------------------------------------------------
-- PACIENTE: nao faz nada direto - toda interacao passa pela aplicacao (app_ia).
-- Em cenarios de acesso direto (BI), so poderia ver o proprio log (RLS).
-- ---------------------------------------------------------------------------
GRANT SELECT ON dbo.medicamento    TO rl_paciente;
GRANT SELECT ON dbo.bula_chunk     TO rl_paciente;
GRANT SELECT ON dbo.consulta_log   TO rl_paciente;   -- restringido pela RLS abaixo
DENY  SELECT, INSERT, UPDATE, DELETE ON dbo.usuario   TO rl_paciente;
DENY  SELECT, INSERT, UPDATE, DELETE ON dbo.feedback  TO rl_paciente;
GO

-- ---------------------------------------------------------------------------
-- FARMACEUTICO: curador do catalogo; ve agregados, NAO ve identidade do usuario
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON dbo.medicamento TO rl_farmaceutico;
GRANT SELECT, INSERT, UPDATE, DELETE ON dbo.bula_chunk TO rl_farmaceutico;
GRANT SELECT ON dbo.consulta_log TO rl_farmaceutico; -- chega mascarado (DDM)
GRANT SELECT ON dbo.feedback     TO rl_farmaceutico;
DENY  SELECT ON dbo.usuario (telegram_id, email) TO rl_farmaceutico;  -- DENY em coluna sensivel
DENY  UPDATE, DELETE ON dbo.consulta_log TO rl_farmaceutico;          -- log e integro/inalteravel
GO

-- ---------------------------------------------------------------------------
-- SUPORTE: leitura operacional. Ve logs SEMPRE mascarados e nao altera nada.
-- ---------------------------------------------------------------------------
GRANT SELECT ON dbo.consulta_log TO rl_suporte;
GRANT SELECT ON dbo.feedback     TO rl_suporte;
DENY  SELECT, INSERT, UPDATE, DELETE ON dbo.usuario      TO rl_suporte;
DENY  INSERT, UPDATE, DELETE ON dbo.medicamento          TO rl_suporte;
DENY  INSERT, UPDATE, DELETE, ALTER ON dbo.bula_chunk    TO rl_suporte;
GO

-- ---------------------------------------------------------------------------
-- APP_IA (n8n/LLM): privilegio minimo absoluto. So executa procedures;
-- nao le tabelas diretamente - nem as publicas. As procedures definem o que
-- a IA pode ver e fazer (ver docs/seguranca.md, matriz ampliada).
-- ---------------------------------------------------------------------------
GRANT EXECUTE ON dbo.usp_buscar_bulas            TO rl_app_ia;
GRANT EXECUTE ON dbo.usp_buscar_medicamento_nome TO rl_app_ia;
GRANT EXECUTE ON dbo.usp_registrar_consulta      TO rl_app_ia;
DENY  SELECT, INSERT, UPDATE, DELETE ON SCHEMA::dbo TO rl_app_ia;  -- fecha todas as tabelas
DENY  ALTER, CONTROL, TAKE OWNERSHIP ON SCHEMA::dbo TO rl_app_ia;  -- anti-escalonamento
GO

-- ---------------------------------------------------------------------------
-- Row-Level Security: log so visivel para o proprio usuario (via contexto de
-- sessao setado em usp_registrar_consulta) ou papeis de operacao.
-- ---------------------------------------------------------------------------
CREATE OR ALTER FUNCTION dbo.fn_rls_consulta (@usuario_id bigint)
RETURNS TABLE WITH SCHEMABINDING
AS
RETURN
    SELECT 1 AS permitido
    WHERE @usuario_id = TRY_CAST(SESSION_CONTEXT(N'usuario_id') AS bigint)  -- dono do log
       OR IS_MEMBER('rl_farmaceutico') = 1
       OR IS_MEMBER('rl_suporte') = 1
       OR IS_MEMBER('db_owner') = 1;
GO

CREATE SECURITY POLICY dbo.pol_rls_consulta
    ADD FILTER PREDICATE dbo.fn_rls_consulta(usuario_id) ON dbo.consulta_log
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
-- Auditoria: toda consulta ja e registrada em dbo.consulta_log pela
-- usp_registrar_consulta (trilha de aplicacao). Opcional extra: SERVER AUDIT
-- para registrar logins e comandos DDL (anti-escalonamento).
-- ---------------------------------------------------------------------------
-- CREATE SERVER AUDIT audit_guiamed TO FILE (FILEPATH = 'C:\auditoria\');
-- ALTER SERVER AUDIT audit_guiamed WITH (STATE = ON);
GO
