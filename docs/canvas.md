# Canvas de uma página — GuiaMed

| Bloco | Conteúdo |
|---|---|
| **Problema** | Pacientes leigos não entendem bulas de medicamentos: linguagem técnica, letras miúdas e dezenas de seções. Resultado real: dose errada, horário errado e combinação de remédios com interação perigosa. |
| **Usuário** | Paciente adulto que usa medicamentos contínuos (ex.: Pressormin para pressão) e cuidadores de familiares idosos. |
| **Entrada** | Pergunta em linguagem natural no Telegram: *"posso tomar Pressormin junto com Dorfanol?"* |
| **Saída** | Resposta curta em linguagem simples, baseada **só na bula cadastrada**, citando medicamento + seção, com a tarja e o aviso "não substitui orientação médica". |
| **Arquitetura em caixas** | `Telegram → n8n (orquestração + registro) → Scope-Guard (fine-tuned) → RAG: embedding → SQL Server 2025 (VECTOR) → GPT-4o-mini → pós-checagem → resposta → log de auditoria` |
| **Maior risco** | **Alucinação**: o LLM responder algo que não está na bula (dose inventada = risco à saúde). Mitigação: RAG obrigatório, regra "sem fonte, sem resposta", pós-checagem com citação de chunk e medição contínua de fidelidade. |
| **Versão mínima (3 semanas)** | Bot no Telegram respondendo **indicação, posologia e interações** das 24 bulas fictícias, com citação de fonte, tudo logado no SQL Server com RLS e mascaramento ativos. Fora do escopo: cadastro de novos medicamentos pelo bot, lembretes de dose, app mobile. |
