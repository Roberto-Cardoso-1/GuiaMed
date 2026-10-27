# ENTREGA — GuiaMed (app de acessibilidade por voz)

**Grupo:** GuiaMed
**Componentes (nome completo – matrícula):**
1. João Roberto Santos Cardoso – 231070711
2. Gabriel de Souza Nascimento – 231071404
3. Daniel Queiroga Santana Martins – 231070654

**Disciplinas cursadas:** Projeto de Inteligência Artificial **e** Segurança e Otimização de BD → entregamos **Parte comum + Parte de IA + Parte de Segurança + Parte de integração** (valem nas duas disciplinas).

**Repositório:** https://github.com/Roberto-Cardoso-1/GuiaMed — branch `main`, repositório público. **Sem chaves de API ou senhas no código** (todas via variáveis de ambiente; ver `README.md` e `backend/.env.example`).

> Interface escolhida: **app Android por voz ("zero interface") + API sem tela (FastAPI)** — formato permitido pelo enunciado ("API sem tela"); não é SaaS, aplicação web nem site. Dados **100% fictícios** (declaração em [docs/dados.md](docs/dados.md)).

---

## PARTE COMUM

1. **Título e problema — GuiaMed:** pessoas com deficiência visual/baixa visão **não conseguem ler embalagens de remédio nem receitas médicas**, e erros de medicamento nesse grupo são risco de vida. **Para quem:** pessoas cegas ou com baixa visão severa (e idosos com dificuldade de leitura). **O que muda quando funciona:** a pessoa aponta a câmera para o remédio ou a receita e **ouve em voz** o que é, para que serve e como tomar — com **alerta de alergia** falado — sem depender de outra pessoa, com base **somente em bulas cadastradas**.
2. **Canvas de uma página:** [docs/canvas.md](docs/canvas.md) (resumo também no [README](README.md)).
3. **Repositório criado** com README de uma página: [README.md](README.md) — link no topo deste documento.

## PARTE DE IA

1. **Diagrama da arquitetura** com LLM, RAG (fontes, chunking, embeddings, banco vetorial, busca), experimento de fine-tuning, fluxo n8n (gatilho → IA → ação → registro) e interface (app voz + API): [docs/arquitetura.md](docs/arquitetura.md).
2. **Escolha do LLM justificada** (GPT-4o-mini; qualidade/custo/privacidade + comparativo) **com estimativa de custo por 1.000 usos = ≈ US$ 0,30 (~R$ 1,70)**: seção "Escolha do LLM" em [docs/arquitetura.md](docs/arquitetura.md).
3. **Dados:** base inicial de **24 bulas fictícias** (mínimo exigido: 20) + dataset sintético de OCR para fine-tuning, com origem e confirmação de que são fictícios: [docs/dados.md](docs/dados.md) + arquivos em [`data/`](data).
4. **Ponto difícil:** onde a IA pode errar (**identificação errada do remédio a partir de OCR ruidoso**, alucinação de dose, prompt injection **impresso na embalagem** lido pela câmera) e como será medido (acurácia de extração ≥ 90%, fidelidade ≥ 95% com checagem de citação, bloqueio de injeção ≥ 95%): seção "Ponto difícil" em [docs/arquitetura.md](docs/arquitetura.md) e suíte em [docs/ataques.md](docs/ataques.md).
5. **Apresentação parcial de 5 min** (canvas + arquitetura): roteiro em [docs/apresentacao.md](docs/apresentacao.md).

## PARTE DE SEGURANÇA

1. **Modelo de dados** com cada campo classificado (público/interno/sensível — incluindo **alergias e receitas, dados de saúde**) e proteção dos sensíveis (DDM = mascaramento, RLS por dispositivo, TDE): [docs/seguranca.md](docs/seguranca.md) §1, implementado em `db/01_ddl.sql` e `db/02_security.sql`.
2. **Matriz de papéis** com 4 papéis + DBA, o que cada um pode/não pode e onde entra o DENY (inclui DENY em coluna e DENY em schema): [docs/seguranca.md](docs/seguranca.md) §2.
3. **Ameaça/gargalo e prova:** vazamento de **dados de saúde via SQL Injection** nos textos livres (OCR/voz) + escalonamento via identidade da API; gargalo na busca vetorial — ambos com plano de prova: [docs/seguranca.md](docs/seguranca.md) §3.
4. **Plano de otimização:** 2 consultas que degradam (Q1 busca vetorial; Q2 relatório de alertas de alergia em 1M de logs) + como gerar volume: [docs/seguranca.md](docs/seguranca.md) §4 e `db/04_volume.sql`.
5. **Base inicial carregada:** SQL Server 2025 com scripts de criação e carga — `db/01_ddl.sql` (DDL + procedures), `db/03_seed.sql` (**gerado**: 24 medicamentos + 144 chunks; regenerável com `python scripts/generate_seed.py`), `db/05_seed_app.sql` (demo do app), `db/02_security.sql` (papéis, DENY, RLS, DDM, TDE). Embeddings: `scripts/embed_chunks.py`.

## PARTE DE INTEGRAÇÃO

1. **Diagrama único** juntando IA e dados (banco ligado ao LLM, ao RAG e ao n8n; permissões, proteção e auditoria destacadas): primeiro diagrama de [docs/arquitetura.md](docs/arquitetura.md).
2. **Matriz de papéis ampliada incluindo a IA:** papel `rl_app_ia` — a IA/API só executa procedures (checagem de alergia, registro, busca), tem DENY em todas as tabelas e não consegue ler alergias nem receitas de ninguém diretamente: [docs/seguranca.md](docs/seguranca.md) §2 (nota ao final).
3. **Ataques a testar:** prompt injection (**direto por voz** e **indireto impresso na embalagem/receita lida pela câmera** — suíte de 30 payloads com metas) e SQL Injection (+ escalonamento de privilégio como complementar): [docs/ataques.md](docs/ataques.md).
