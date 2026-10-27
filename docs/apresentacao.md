# Roteiro da apresentação parcial (5 min) — Parte IA

> Mostrar o **canvas** e a **arquitetura**. Sugestão de divisão do tempo (3 apresentadores):

**0:00–0:40 — Problema (membro 1 — João Roberto)**
- Demo ao vivo se possível: apontar a câmera para uma embalagem → o app **fala** o que é.
- "Quem não enxerga não lê bula, embalagem nem receita. Erro de remédio aqui não é inconveniente — é risco de vida."
- Solução: app por voz que identifica o remédio pela câmera e **só fala o que está na bula cadastrada** — com alerta de alergia.

**0:40–1:20 — Canvas (membro 1)**
- Mostrar `docs/canvas.md` (1 slide): entrada (câmera/voz), saída (voz + alerta), maior risco (identificar remédio errado) e versão mínima de 3 semanas.

**1:20–3:20 — Arquitetura (membro 2 — Gabriel)**
- Mostrar o diagrama único (`docs/arquitetura.md`) e caminhar pelo fluxo:
  App (voz/OCR) → API → n8n → **Extrator (fine-tuned)** → **checagem de alergia** → **RAG** (embedding → SQL Server `VECTOR`) → **GPT-4o-mini** → pós-checagem → **resposta falada** → **log de auditoria**.
- Destacar as 5 coisas que a banca vai procurar: LLM escolhido, RAG (fontes/chunking/embeddings/banco vetorial/busca), fine-tuning, fluxo n8n, interface (API sem tela — permitida no enunciado).

**3:20–4:10 — Decisões e números (membro 3 — Daniel)**
- Por que GPT-4o-mini (custo/qualidade/privacidade de dado de saúde): **US$ 0,30 por 1.000 usos** (~R$1,70).
- Base de 24 bulas **fictícias** (aviso em cada arquivo) — LGPD ok por construção.
- Fine-tuning do extrator de OCR: 300 pares sintéticos com ruído de câmera, acurácia por campo ≥ 90%, treino ~US$ 0,68.

**4:10–5:00 — Ponto difícil e fechamento (membro 2)**
- Onde a IA erra: identificação errada via OCR ruidoso, alucinação de dose, **prompt injection impresso na embalagem** (a câmera lê comando hostil) → como medimos (acurácia ≥90%, fidelidade ≥95%, bloqueio ≥95% com suíte de 30 ataques).
- Frase final: "Quem não enxerga não pode conferir duas vezes — então o GuiaMed não chuta: se não está na bula, o app não fala."

## Preparação rápida

- [x] Adicionar os integrantes no README.
- [ ] Exportar o diagrama Mermaid como imagem (draw.io/Mermaid Live) ou abrir o `.md` no GitHub renderizado.
- [ ] Gravar vídeo curto do app no celular (mesmo em modo dev) — 15s de voz funcionando valem mais que 10 slides.
- [ ] Testar o banco local com `db/01` → `db/03` → `db/05` → `db/02` antes da aula (demo opcional de um SELECT mascarado impressiona em Segurança).
- [ ] Cada membro deve saber defender: por que fine-tuning para **extração** (e não para "ensinar medicina"); por que 1 chunk = 1 seção; por que device_id anônimo em vez de login; por que DENY no schema inteiro para a identidade da IA.
