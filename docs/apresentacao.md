# Roteiro da apresentação parcial (5 min) — Parte IA

> Mostrar o **canvas** e a **arquitetura**. Sugestão de divisão do tempo:

**0:00–0:40 — Problema (quem apresenta: membro 1)**
- "Paciente leigo não entende bula: erra dose, mistura remédio com interação."
- Solução: bot no Telegram que responde **só com base nas bulas cadastradas**, citando a fonte.

**0:40–1:20 — Canvas (membro 1)**
- Mostrar `docs/canvas.md` (1 slide): entrada, saída, maior risco (alucinação) e versão mínima de 3 semanas.

**1:20–3:20 — Arquitetura (membro 2)**
- Mostrar o diagrama único (`docs/arquitetura.md`) e caminhar pelo fluxo:
  Telegram → n8n → **Scope-Guard (fine-tuned)** → **RAG** (embedding → SQL Server `VECTOR`) → **GPT-4o-mini** → pós-checagem → resposta → **log de auditoria**.
- Destacar as 4 coisas que a banca vai procurar: LLM escolhido, RAG (fontes/chunking/embeddings/banco vetorial/busca), fine-tuning, fluxo n8n.

**3:20–4:10 — Decisões e números (membro 3)**
- Por que GPT-4o-mini (custo/qualidade/privacidade): **US$ 0,44 por 1.000 usos** (~R$2,50).
- Base de 24 bulas **fictícias** (aviso em cada arquivo) — LGPD ok por construção.
- Fine-tuning (Scope-Guard): 240 exemplos, F1 macro, custo do treino ~US$ 0,43.

**4:10–5:00 — Ponto difícil e fechamento (membro 4)**
- Onde a IA erra: alucinação, fora de escopo, prompt injection → como medimos (fidelidade ≥95%, F1 ≥0,95, bloqueio ≥95% com suíte de 30 ataques).
- Frase final: "Sem fonte, sem resposta — se não está na bula, o GuiaMed não responde."

## Preparação rápida

- [ ] Adicionar os integrantes no README.
- [ ] Exportar o diagrama Mermaid como imagem (draw.io/Mermaid Live) ou abrir o `.md` no GitHub renderizado.
- [ ] Testar o banco local com `db/01` → `db/03` → `db/02` antes da aula (demo opcional de um SELECT mascarado impressiona em Segurança).
- [ ] Cada membro deve saber defender: por que 1 chunk = 1 seção; por que fine-tuning para classificação e não para "ensinar medicina"; por que DENY no schema inteiro.
