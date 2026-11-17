# Experimento local de ajuste supervisionado

Este registro descreve o experimento inicial de classificação. A entrega foi
complementada com o [fine-tuning de um LLM pré-treinado](finetuning/README.md),
que possui dados separados de treino/teste, logs e comparação antes/depois.

O experimento usa somente as 24 bulas fictícias do projeto. Foram gerados 300
textos OCR sintéticos e rotulados, com 240 exemplos de treino e 60 de teste.

Neste experimento inicial foi ajustado localmente um classificador Naive Bayes
de n-gramas de caracteres para
identificação do medicamento em OCR ruidoso.

| Medição | Resultado |
|---|---:|
| Acurácia do baseline por correspondência exata | 80.00% |
| Acurácia do modelo ajustado | 100.00% |
| Ganho | 20.00 pontos percentuais |
| Tempo de treino | 15.804 ms |
| Classes | 24 |

Os resultados completos e parâmetros estão em `metricas.json`; o dataset está
versionado em `data/ft/ocr_finetuning_local.jsonl`.
