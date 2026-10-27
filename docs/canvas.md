# Canvas de uma página — GuiaMed (acessibilidade por voz)

| Bloco | Conteúdo |
|---|---|
| **Problema** | Pessoas cegas ou com baixa visão severa **não conseguem ler** embalagens de remédio, bulas nem receitas médicas. Consequência real: tomar o remédio errado, dose errada, ou usar um medicamento ao qual é **alérgico** — sem ter como conferir sozinho. |
| **Usuário** | Pessoa com deficiência visual ou baixa visão severa que usa medicamentos no dia a dia; também idosos com pouca visão e dificuldade de leitura. |
| **Entrada** | **Câmera do celular**: foto da embalagem do remédio ou da receita digitalizada (OCR Tesseract roda no próprio aparelho) + **comandos de voz** (ex.: cadastrar "sou alérgico a dipirona" falando). |
| **Saída** | **Voz (TTS)**: nome do remédio, para que serve, como tomar, principais alertas — em linguagem simples, baseada **só na bula cadastrada**, citando a fonte — mais **alerta sonoro imediato** se o remédio conflitar com as alergias do usuário. Visor em alto contraste (preto/amarelo) como apoio para baixa visão. |
| **Arquitetura em caixas** | `App Android (câmera/OCR/voz) → API FastAPI (sem tela) → n8n (orquestração) → Extrator fine-tuned (OCR → remédio) → checagem de alergia no banco → RAG: embedding → SQL Server 2025 (VECTOR) → GPT-4o-mini (resumo p/ fala com fonte) → resposta → app fala → log de auditoria` |
| **Maior risco** | **Identificação errada do medicamento** a partir de OCR ruidoso (foto escura/torta) — ouvir o nome de um remédio e ter outro na mão é perigoso. Mitigação: fine-tuning dedicado à extração, limiar de confiança responde "não identifiquei, repita a foto", RAG obrigatório com citação, e pós-checagem determinística. |
| **Versão mínima (3 semanas)** | App aponta a câmera para a embalagem → ouve **nome, indicação e posologia** das 24 bulas fictícias; cadastro de alergia **por voz** com alerta falado em caso de conflito; tudo logado no SQL Server com RLS e mascaramento ativos. Fora do escopo: leitura de receita com múltiplos medicamentos, lembretes de dose, conta com login (o app usa identidade anônima do aparelho). |
