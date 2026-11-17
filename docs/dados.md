# Base de documentos — GuiaMed (Parte IA › Dados)

## O que é a base

Dois conjuntos, ambos sintéticos:

1. **24 bulas de medicamentos fictícios**, cada uma com 6 seções (indicação, posologia, contraindicações, efeitos adversos, interações, advertências) — superando o mínimo exigido de 20 itens. É a base do **RAG**: o que o app "lê em voz alta" quando a pessoa aponta a câmera para um remédio.
   - Fonte única estruturada: [`data/bulas.json`](../data/bulas.json)
   - Versão documento (RAG): [`data/bulas_md/`](../data/bulas_md/) (24 arquivos `.md`)
   - Índice local: `backend/local_rag.py` → 24 medicamentos e 144 chunks

2. **Dataset para ajuste supervisionado local** com 300 textos de OCR ruidoso
   e rótulo do medicamento, gerado a partir das 24 bulas. O arquivo completo
   está em [`data/ft/ocr_finetuning_local.jsonl`](../data/ft/ocr_finetuning_local.jsonl)
   e é reproduzível por `scripts/run_ai_evidence.py`.

Para o fine-tuning do LLM, `data/ft/llm_train.jsonl` contém 252 entradas e
`data/ft/llm_test.jsonl` contém 48 entradas, duas por classe. A separação usa
semente 20261117 e verifica a ausência de textos idênticos entre conjuntos.
Ambos usam a mesma família de templates sintéticos, uma limitação da avaliação.

## Inventário das bulas

| # | Medicamento (fictício) | Classe terapêutica | Tarja |
|---|---|---|---|
| 1 | Pressormin | anti-hipertensivo | vermelha |
| 2 | Gliforin | antidiabético oral | vermelha |
| 3 | Dorfanol | analgésico/antitérmico | isenta |
| 4 | Cefatrax | antienxaquecoso | vermelha |
| 5 | Somnivale | fitoterápico calmante | isenta |
| 6 | Ventacid | antiácido/antiflatulento | isenta |
| 7 | Alergonix | anti-histamínico | isenta |
| 8 | Asmaplus | broncodilatador inalatório | vermelha |
| 9 | Cardiovast | estatina | vermelha |
| 10 | Tireonorm | hormônio tireoidiano | vermelha |
| 11 | Ferronato | suplemento de ferro | isenta |
| 12 | Vitabex | complexo B | isenta |
| 13 | Ciclofem | anticoncepcional oral | vermelha |
| 14 | Ansioplant | fitoterápico ansiolítico | isenta |
| 15 | Reumaxil | imunomodulador (DMARD) | vermelha |
| 16 | Dermatopol | antibiótico tópico | vermelha |
| 17 | Oftalmil | lubrificante ocular | isenta |
| 18 | Probionex | probiótico | isenta |
| 19 | Fungitrat | antifúngico tópico | vermelha |
| 20 | Tosseclear | expectorante | isenta |
| 21 | Venocalm | flebotônico | isenta |
| 22 | Insulfort | insulina intermediária | vermelha |
| 23 | Cortiderm | corticoide tópico | vermelha |
| 24 | Migranol | betabloqueador (profilaxia) | vermelha |

Alguns itens foram criados propositalmente com **interações entre si** (ex.:
Tireonorm × Ventacid/Ferronato; Asmaplus × Migranol) para testar o RAG em casos
difíceis.

## Origem

Base **sintética**: redigida pelo grupo com auxílio de IA generativa, usando como referência apenas a **estrutura pública de bulas** (layout de seções da ANVISA), sem copiar conteúdo de bula real. Os pares de OCR foram gerados por script a partir dessas bulas. **Não há dados de pacientes, receitas ou prontuários reais.**

## Confirmação de que é fictícia

1. Nomes comerciais **e** princípios ativos foram **inventados** (ex.: "losaprilato de sódio", "nervastatina cálcica").
2. Cada arquivo `.md` carrega o aviso: *"Bula fictícia criada para fins didáticos. Não é um medicamento real."*
3. O `data/bulas.json` abre com `_aviso` declarando a natureza sintética da base.
4. Nenhum dado pessoal (real ou não) existe na base — as "pessoas" dos scripts de demo (`db/05_seed_app.sql`) são dispositivos anônimos inventados.

> Qualquer semelhança com medicamentos comercializados é mera coincidência. O app é uma ferramenta de acessibilidade para leitura de informação já disponível na bula — **não dá diagnóstico nem prescreve**.
