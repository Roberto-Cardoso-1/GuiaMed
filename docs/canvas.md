# Canvas de uma página — GuiaMed (acessibilidade por voz)

| Bloco | Conteúdo |
|---|---|
| **Problema** | Pessoas cegas ou com baixa visão severa **não conseguem ler** embalagens de remédio, bulas nem receitas médicas. Consequência real: tomar o remédio errado, dose errada, ou usar um medicamento ao qual é **alérgico** — sem ter como conferir sozinho. |
| **Usuário** | Pessoa com deficiência visual ou baixa visão severa que usa medicamentos no dia a dia; também idosos com pouca visão e dificuldade de leitura. |
| **Entrada** | **Câmera do celular**: foto da embalagem do remédio ou da receita digitalizada (OCR Tesseract roda no próprio aparelho) + **comandos de voz** (ex.: cadastrar "sou alérgico a dipirona" falando). |
| **Saída** | **Voz (TTS)**: nome do remédio, para que serve, como tomar, principais alertas — em linguagem simples, baseada **só na bula cadastrada**, citando a fonte — mais **alerta sonoro imediato** se o remédio conflitar com as alergias do usuário. Visor em alto contraste (preto/amarelo) como apoio para baixa visão. |
| **Arquitetura em caixas** | `App Android offline (câmera → ML Kit → parser → voz) + n8n local → FastAPI → RAG TF-IDF nas 24 bulas fictícias → resposta citada → auditoria PostgreSQL` |
| **Maior risco** | **Identificação errada do medicamento** a partir de OCR ruidoso. Mitigação: classificador ajustado localmente, limiar conservador, RAG com citação e recusa quando não há fonte. |
| **Versão mínima executada** | App lê embalagem/receita e alergias offline; fluxo acadêmico local consulta 144 chunks, cita fonte e registra auditoria anonimizada com RLS e mascaramento. |
