# Evidências de Segurança e Integração

**Grupo GuiaMed — João Roberto Santos Cardoso (231070711), Gabriel de Souza Nascimento (231071404), Daniel Queiroga Santana Martins (231070654).**

Os scripts SQL usam exclusivamente dados fictícios. Execute `db/01_ddl.sql`, `db/03_seed.sql`, `db/05_seed_app.sql` e `db/02_security.sql` nessa ordem, informando a senha do usuário de serviço fora do repositório.

## 1. IA/API sem acesso direto a dados sensíveis

Conectado como `svc_guiamed_ia` (papel `rl_app_ia`), o seguinte comando deve falhar por permissão negada:

```sql
USE GuiaMed;
SELECT * FROM dbo.alergia;
SELECT * FROM dbo.receita;
```

O mesmo perfil pode fazer somente a consulta necessária via procedure parametrizada, sem receber a tabela inteira:

```sql
EXEC dbo.usp_checar_alergia
    @device_id = N'dev-demo-0001',
    @principio_ativo = N'reumexato',
    @nome_comercial = N'Reumaxil';
```

Resultado esperado: uma linha contendo apenas `conflito` e `substancia_em_conflito`. A regra é reforçada por `DENY SELECT, INSERT, UPDATE, DELETE ON SCHEMA::dbo TO rl_app_ia` e por permissões somente de `EXECUTE` nas procedures.

## 2. Registro inicial de auditoria

Ainda como `svc_guiamed_ia`, registre uma consulta fictícia:

```sql
EXEC dbo.usp_registrar_consulta
    @device_id = N'dev-demo-0001',
    @entrada_ocr = N'Reumaxil 20 mg',
    @resposta_voz = N'Alerta de alergia fictício.',
    @alerta_alergia = 1,
    @extracao_json = N'{"nome":"Reumaxil"}',
    @fonte_chunk_ids = N'1,2',
    @modelo = N'ocr-local',
    @canal = 'app_voz';
```

Como DBA, confirme a evidência:

```sql
SELECT TOP (1) dispositivo_id, canal, alerta_alergia, extracao_json,
       fonte_chunk_ids, modelo, criado_em
FROM dbo.consulta_log
ORDER BY id DESC;
```

Esse registro demonstra **quem** solicitou (`dispositivo_id` anonimizado), **o que** a camada de IA/API consultou (`extracao_json`, `fonte_chunk_ids`) e **quando** (`criado_em`). Para a entrega, anexar prints dos três blocos: erro de permissão, resultado da procedure e linha de auditoria.

## Limite conhecido

O app Android V1 usa PostgreSQL opcional para a demonstração de alergias. O modelo com RBAC, RLS, DDM e auditoria está entregue nos scripts locais de SQL Server em `db/`; a migração dessa política para o backend de produção permanece como próximo passo, sem alegar que ela já está ativada no telefone de demonstração.
