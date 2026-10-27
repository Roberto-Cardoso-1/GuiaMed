# ENTREGA — GuiaMed

**Grupo:** GuiaMed
**Componentes (nome completo – matrícula):**
1. João Roberto Santos Cardoso – 231070711
2. Gabriel de Souza Nascimento – 231071404
3. [PENDENTE – nome completo + matrícula]
4. [PENDENTE – nome completo + matrícula]

**Disciplinas cursadas:** Projeto de Inteligência Artificial **e** Segurança e Otimização de BD → entregamos **Parte comum + Parte de IA + Parte de Segurança + Parte de integração** (valem nas duas disciplinas).

**Repositório:** https://github.com/Roberto-Cardoso-1/GuiaMed — branch `main`, repositório público. **Sem chaves de API ou senhas no código** (todas via variáveis de ambiente; ver `README.md`).

> Interface escolhida: **bot de Telegram** (permitido pelo enunciado — não é SaaS/web). Dados **100% fictícios** (declaração em [docs/dados.md](docs/dados.md)).

---

## PARTE COMUM

1. **Título e problema — GuiaMed:** pacientes leigos não entendem bulas de medicamentos e cometem erros de automedicação (dose errada, interações perigosas). **Para quem:** pacientes com medicamentos contínuos e cuidadores. **O que muda quando funciona:** resposta confiável em linguagem simples, em segundos, no Telegram, citando bula e seção — sem fonte, sem resposta.
2. **Canvas de uma página:** [docs/canvas.md](docs/canvas.md) (resumo também no [README](README.md)).
3. **Repositório criado** com README de uma página: [README.md](README.md) (link no topo deste documento após publicação).

## PARTE DE IA

1. **Diagrama da arquitetura** com LLM, RAG (fontes, chunking, embeddings, banco vetorial, busca), experimento de fine-tuning, fluxo n8n (gatilho → IA → ação → registro) e interface: [docs/arquitetura.md](docs/arquitetura.md).
2. **Escolha do LLM justificada** (GPT-4o-mini; qualidade/custo/privacidade + comparativo) **com estimativa de custo por 1.000 usos = ≈ US$ 0,44 (~R$ 2,50)**: seção "Escolha do LLM" em [docs/arquitetura.md](docs/arquitetura.md).
3. **Dados:** base inicial de **24 bulas fictícias** (mínimo exigido: 20), origem sintética e confirmação formal de que é fictícia: [docs/dados.md](docs/dados.md) + arquivos em [`data/`](../data).
4. **Ponto difícil:** onde a IA pode errar (alucinação, fora do escopo, prompt injection) e como será medido (fidelidade ≥ 95% com checagem de citação; F1 ≥ 0,95 no classificador; bloqueio de injeção ≥ 95%): seção "Ponto difícil" em [docs/arquitetura.md](docs/arquitetura.md) e suíte em [docs/ataques.md](docs/ataques.md).
5. **Apresentação parcial de 5 min** (canvas + arquitetura): roteiro em [docs/apresentacao.md](docs/apresentacao.md).

## PARTE DE SEGURANÇA

1. **Modelo de dados** com cada campo classificado (público/interno/sensível) e proteção dos sensíveis (DDM = mascaramento, RLS, TDE): [docs/seguranca.md](docs/seguranca.md) §1, implementado em `db/01_ddl.sql` e `db/02_security.sql`.
2. **Matriz de papéis** com 4 papéis + DBA, o que cada um pode/não pode e onde entra o DENY (inclui DENY em coluna e DENY em schema): [docs/seguranca.md](docs/seguranca.md) §2.
3. **Ameaça/gargalo e prova:** SQL Injection + escalonamento via identidade da IA; gargalo na busca vetorial — ambos com plano de prova: [docs/seguranca.md](docs/seguranca.md) §3.
4. **Plano de otimização:** 2 consultas que degradam (Q1 busca vetorial; Q2 agregação de 1M de logs) + como gerar volume: [docs/seguranca.md](docs/seguranca.md) §4 e `db/04_volume.sql`.
5. **Base inicial carregada:** SQL Server 2025 com scripts de criação e carga — `db/01_ddl.sql` (DDL + procedures), `db/03_seed.sql` (**gerado**: 24 medicamentos + 144 chunks; regenerável com `python scripts/generate_seed.py`), `db/02_security.sql` (papéis, DENY, RLS, DDM, TDE). Embeddings: `scripts/embed_chunks.py`.

## PARTE DE INTEGRAÇÃO

1. **Diagrama único** juntando IA e dados (banco ligado ao LLM, ao RAG e ao n8n; permissões, proteção e auditoria destacadas): primeiro diagrama de [docs/arquitetura.md](docs/arquitetura.md).
2. **Matriz de papéis ampliada incluindo a IA:** papel `rl_app_ia` — a IA só executa 3 procedures, tem DENY em todas as tabelas e não consegue ler usuários nem alterar schema: [docs/seguranca.md](docs/seguranca.md) §2 (nota ao final).
3. **Ataques a testar:** prompt injection (direto + indireto via bula envenenada, suíte de 30 payloads com metas) e SQL Injection (+ escalonamento de privilégio como complementar):
   [docs/ataques.md](docs/ataques.md).
