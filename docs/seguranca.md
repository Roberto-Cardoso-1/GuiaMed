# Segurança e Otimização — GuiaMed

Banco escolhido: **SQL Server 2025** (Docker local ou Azure SQL). Justificativa: um único banco atende dados transacionais, vetor (RAG) e auditoria, e oferece nativamente os três mecanismos de proteção exigidos: **DDL** (`db/01_ddl.sql`), **RLS + DDM + RBAC** (`db/02_security.sql`), **script de carga** (`db/03_seed.sql`, gerado).

## 1. Modelo de dados com classificação

| Tabela | Coluna | Tipo | Classificação | Proteção prevista |
|---|---|---|---|---|
| **usuario** | id | bigint | interno | PK |
| | telegram_id | nvarchar(32) | **sensível** | DDM `partial(0,"***",2)` + UNIQUE |
| | nome | nvarchar(120) | interno | acesso só via app |
| | email | nvarchar(256) | **sensível** | DDM `email()` |
| | papel, criado_em | — | interno | — |
| **medicamento** | todas | — | **público** | catálogo aberto de bulas fictícias |
| **bula_chunk** | conteudo | nvarchar(max) | **público** | conteúdo de bula |
| | embedding | VECTOR(1536) | interno | artefato do sistema, sem valor fora do RAG |
| **consulta_log** | pergunta | nvarchar(max) | **sensível** | DDM `partial(10," (...) ",0)` + RLS por usuário + TDE |
| | resposta | nvarchar(max) | **sensível** | idem + retenção de 90 dias |
| | fonte_chunk_ids, scope_guard | — | interno | trilha do RAG |
| | modelo, tokens_in/out, custo_usd | — | interno | métricas agregadas liberadas para farmacêutico |
| | usuario_id, canal, criado_em | — | interno | FK + índice |
| **feedback** | nota | tinyint | interno | — |
| | comentario | nvarchar(500) | **sensível** | DDM `default()` |
| **(banco inteiro)** | — | — | — | **TDE** (criptografia em repouso) + TLS obrigatório na conexão |

Mecanismos: mascaramento dinâmico (DDM) nas colunas da tabela; **RLS** por política `pol_rls_consulta` (cada usuário só vê o próprio log via `SESSION_CONTEXT`); **criptografia**: TDE no banco + TLS em trânsito. Coluna sensível nunca é exposta a quem não precisa — ver matriz.

## 2. Matriz de papéis (4 papéis + DBA)

| Papel | Pode | Não pode | Onde entra o DENY |
|---|---|---|---|
| **rl_paciente** (usuário final, via app) | SELECT em `medicamento`, `bula_chunk`; SELECT no **próprio** `consulta_log` (RLS) | Ver log de terceiros; mexer em `usuario`, `feedback` | `DENY SELECT,INSERT,UPDATE,DELETE ON usuario/feedback` |
| **rl_farmaceutico** (curador de bulas) | INSERT/UPDATE em `medicamento`, `bula_chunk`; SELECT em `consulta_log` e `feedback` (**mascarados** por DDM) | Ver `telegram_id`/`email`; alterar/apagar log | `DENY SELECT ON usuario(telegram_id,email)` — DENY **em coluna**; `DENY UPDATE,DELETE ON consulta_log` |
| **rl_suporte** (operação) | SELECT em `consulta_log`/`feedback` mascarados | Ver usuários; alterar qualquer dado | `DENY SELECT,IUD ON usuario`; `DENY IUD,ALTER ON medicamento/bula_chunk` |
| **rl_app_ia** (identidade da IA/n8n) | **Apenas EXEC** das 3 procedures (`usp_buscar_bulas`, `usp_buscar_medicamento_nome`, `usp_registrar_consulta`) | Ler/escrever qualquer tabela diretamente; ALTER/CONTROL no schema | `DENY SELECT,INSERT,UPDATE,DELETE ON SCHEMA::dbo`; `DENY ALTER,CONTROL,TAKE OWNERSHIP` |
| **db_owner (DBA)** | Administra objetos | — | Mesmo o DBA vê colunas mascaradas por padrão; `UNMASK` exige permissão específica e fica auditado |

> Versão ampliada com "o que a IA pode ver e fazer" (Parte de integração): a IA enxerga o mundo **somente pelas 3 procedures** — nunca por SELECT direto. Ela lê bulas públicas e escreve auditoria, e não consegue sequer listar usuários: `app_ia` recebe DENY total em tabelas.

## 3. Ameaça e gargalo — e como provamos a solução

**Ameaça principal: SQL Injection + escalonamento de privilégio pela identidade da IA.**
O bot recebe texto livre de desconhecidos; se uma query for concatenada, um usuário injeta SQL. E se a identidade `app_ia` tiver privilégio demais, comprometer o fluxo n8n = comprometer o banco.
**Prova da mitigação:** (1) toda interação passa por procedures **100% parametrizadas** — teste de invasão com payloads (`' OR 1=1--`, `'; DROP TABLE--`, sqlmap) deve falhar em alterar dados; (2) login como `app_ia` executando `SELECT * FROM usuario` / `EXEC sp_addrolemember` deve retornar **erro de permissão** (evidência: print + log); procedimento completo em [ataques.md](ataques.md).

**Gargalo principal: busca vetorial com crescimento da base** (Q1 abaixo) — medido e otimizado no plano seguinte.

## 4. Plano de otimização

**Volume gerado** por `db/04_volume.sql`: ~**1.000.000** linhas em `consulta_log` (batches de 20k) e **50.000** vetores sintéticos em `bula_chunk`. Medição com `SET STATISTICS IO, TIME ON` + plano de execução, antes e depois.

| Query | Por que fica lenta | Medição esperada | Otimização |
|---|---|---|---|
| **Q1 — busca vetorial** `usp_buscar_bulas` (`VECTOR_DISTANCE` + ORDER BY top-5) | Full scan computando cosseno em 50k vetores 1536-d e sort completo | Baseline: scan de `bula_chunk`, milhares de logical reads | Pré-filtro por medicamento (`WHERE medicamento_id = …`, altamente seletivo) + **índice vetorial DiskANN** (`CREATE VECTOR INDEX … METRIC='cosine'`) |
| **Q2 — relatório de uso** agregação por dia/modelo em 1M de linhas (`CAST(criado_em AS date)`) | Scan sequencial de heap/cluster + agregação de todas as colunas | Baseline: 1M+ logical reads, sort/hash grande | **Columnstore não clusterizado** em `(criado_em, modelo, tokens_in, tokens_out, custo_usd)` — agregações analíticas passam a ler só os segmentos necessários |
