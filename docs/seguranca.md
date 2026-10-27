# Segurança e Otimização — GuiaMed (app de acessibilidade)

Banco escolhido: **SQL Server 2025** (Docker local ou Azure SQL). Justificativa: um único banco atende bulas (RAG vetorial), **dados de saúde** (alergias, receitas) e auditoria, e oferece nativamente os três mecanismos de proteção exigidos: **DDL** (`db/01_ddl.sql`), **RLS + DDM + RBAC** (`db/02_security.sql`), **scripts de carga** (`db/03_seed.sql` gerado + `db/05_seed_app.sql` demo).

> Enquadramento LGPD: **alergia a medicamento e receita médica são dados de SAÚDE** — categoria sensível (art. 11). É o que mais pesa na classificação abaixo.

## 1. Modelo de dados com classificação

| Tabela | Coluna | Tipo | Classificação | Proteção prevista |
|---|---|---|---|---|
| **dispositivo** | id | bigint | interno | PK |
| | device_id | nvarchar(64) | **sensível** | DDM `partial(0,"***",4)` + UNIQUE (identifica o aparelho da pessoa; não guardamos nome, email nem login) |
| | apelido, criado_em | — | interno | apelido opcional falado pelo usuário |
| **medicamento** | todas | — | **público** | catálogo aberto de bulas fictícias |
| **bula_chunk** | conteudo | nvarchar(max) | **público** | conteúdo de bula |
| | embedding | VECTOR(1536) | interno | artefato do sistema, sem valor fora do RAG |
| **alergia** | substancia | nvarchar(160) | **sensível (saúde)** | DDM `default()` + RLS por dispositivo + TDE |
| | dispositivo_id, origem, criada_em | — | interno | FK + índice |
| **receita** | texto_ocr | nvarchar(max) | **sensível (saúde)** | DDM `partial(10," (...) ",0)` + RLS + TDE |
| | medicamentos_json | nvarchar(max) | **sensível (saúde)** | idem (medicamentos extraídos da receita) |
| | dispositivo_id, criada_em | — | interno | FK |
| **consulta_log** | entrada_ocr | nvarchar(max) | **sensível** | DDM `partial(10," (...) ",0)` + RLS + TDE |
| | resposta_voz | nvarchar(max) | **sensível** | idem + retenção de 90 dias |
| | alerta_alergia, fonte_chunk_ids, extracao_json | — | interno | trilha do fluxo |
| | modelo, tokens_in/out, custo_usd | — | interno | métricas agregadas liberadas para farmacêutico |
| | dispositivo_id, criado_em | — | interno | FK + índice |
| **(banco inteiro)** | — | — | — | **TDE** (criptografia em repouso) + TLS obrigatório na conexão |

Mecanismos: mascaramento dinâmico (DDM) nas colunas da tabela; **RLS** por política `pol_rls_app` (cada dispositivo só vê os próprios dados via `SESSION_CONTEXT` fixado em `usp_registrar_consulta`); **criptografia**: TDE no banco + TLS em trânsito. Decisão de privacidade por design: **o app não tem login** — a identidade é o `device_id` opaco do aparelho, minimizando dado pessoal desde a coleta.

## 2. Matriz de papéis (4 papéis + DBA)

| Papel | Pode | Não pode | Onde entra o DENY |
|---|---|---|---|
| **rl_paciente** (usuário, via app) | Uso indireto pelo app; em acesso direto (BI): SELECT em `medicamento`, `bula_chunk` e nos **próprios** dados (`alergia`, `receita`, `consulta_log` — RLS) | Ver dados de terceiros; alterar qualquer tabela | `DENY INSERT,UPDATE,DELETE` em `dispositivo/alergia/receita/consulta_log` |
| **rl_farmaceutico** (curador de bulas) | INSERT/UPDATE em `medicamento`, `bula_chunk`; SELECT em `consulta_log`, `alergia` (**mascarados** por DDM) para estatística | Ver `device_id`, `texto_ocr` da receita legível; alterar/apagar log; ver quem é alérgico a quê | `DENY SELECT ON dispositivo(device_id)`; `DENY SELECT ON receita` — DENY **em tabela sensível**; `DENY UPDATE,DELETE ON consulta_log` |
| **rl_suporte** (operação) | SELECT em `consulta_log` mascarado (métricas, falhas do extrator) | Ver alergias, receitas, dispositivos; alterar qualquer dado | `DENY SELECT,IUD ON alergia/receita/dispositivo`; `DENY IUD,ALTER ON medicamento/bula_chunk` |
| **rl_app_ia** (identidade da API + IA/n8n) | **Apenas EXEC** das procedures (`usp_obter_ou_criar_dispositivo`, `usp_registrar_alergia`, `usp_checar_alergia`, `usp_registrar_receita`, `usp_buscar_bulas`, `usp_registrar_consulta`) | Ler/escrever qualquer tabela diretamente — **nem as públicas**; ALTER/CONTROL no schema | `DENY SELECT,INSERT,UPDATE,DELETE ON SCHEMA::dbo`; `DENY ALTER,CONTROL,TAKE OWNERSHIP` |
| **db_owner (DBA)** | Administra objetos | — | Mesmo o DBA vê colunas mascaradas por padrão; `UNMASK` exige permissão específica e fica auditado |

> Versão ampliada com "o que a IA pode ver e fazer" (Parte de integração): a IA enxerga o mundo **somente pelas 6 procedures** — nunca por SELECT direto. Ela recebe o texto OCR, devolve JSON/resposta, checa alergia **sem nunca listar a tabela `alergia`**, e escreve auditoria. Comprometer o fluxo n8n **não** entrega o banco: `app_ia` tem DENY total em tabelas.

## 3. Ameaça e gargalo — e como provamos a solução

**Ameaça principal: vazamento de dados de saúde (alergias/receitas) via SQL Injection + escalonamento de privilégio pela identidade da API.**
O app recebe **texto livre de OCR e de voz** — ou seja, payloads de injeção podem vir **fotografados na rua** (uma embalagem/ papel com `'; DROP TABLE--` impresso) ou falados. Se a identidade `app_ia` tiver privilégio demais, comprometer a API = comprometer dados de saúde.
**Prova da mitigação:** (1) toda interação passa por procedures **100% parametrizadas** — teste de invasão com payloads (`' OR 1=1--`, `'; DROP TABLE--`, sqlmap contra `/verificar` e `/alergias`) deve falhar em alterar dados, com os payloads aparecendo como **texto literal** no log; (2) login como `app_ia` executando `SELECT * FROM alergia` / `EXEC sp_addrolemember` deve retornar **erro de permissão** (evidência: print + log); procedimento completo em [ataques.md](ataques.md).

**Gargalo principal: busca vetorial com crescimento da base** (Q1 abaixo) — medido e otimizado no plano seguinte.

## 4. Plano de otimização

**Volume gerado** por `db/04_volume.sql`: ~**1.000.000** linhas em `consulta_log` (batches de 20k) e **50.000** vetores sintéticos em `bula_chunk`. Medição com `SET STATISTICS IO, TIME ON` + plano de execução, antes e depois.

| Query | Por que fica lenta | Medição esperada | Otimização |
|---|---|---|---|
| **Q1 — busca vetorial** `usp_buscar_bulas` (`VECTOR_DISTANCE` + ORDER BY top-5) | Full scan computando cosseno em 50k vetores 1536-d e sort completo; na voz, latência > 2s já quebra a experiência | Baseline: scan de `bula_chunk`, milhares de logical reads | Pré-filtro por medicamento extraído do OCR (`WHERE medicamento_id = …`, altamente seletivo) + **índice vetorial DiskANN** (`CREATE VECTOR INDEX … METRIC='cosine'`) |
| **Q2 — relatório de alertas de alergia** agregação por dia/substância em 1M de linhas (`CAST(criado_em AS date)`, `alerta_alergia = 1`) | Scan sequencial + agregação de todas as colunas do log | Baseline: 1M+ logical reads, sort/hash grande | **Columnstore não clusterizado** em `(criado_em, alerta_alergia, modelo, tokens_in, tokens_out, custo_usd)` — agregações analíticas leem só os segmentos necessários |
