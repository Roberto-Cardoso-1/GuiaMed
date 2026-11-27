# Comparação dos quatro cenários de IA

Todos os cenários usam o SmolLM2-135M-Instruct fixado na revisão
`12fd25f77366fa6b3b4b768ec3050bf629380bac`, CPU local e cobrança externa zero.

| Cenário | Benchmark | Acerto | Latência | Custo externo/1.000 |
|---|---|---:|---:|---:|
| Modelo base | 48 OCRs sintéticos, geração direta | 7/48 (14,58%) | média 866,972 ms | US$ 0 |
| Base + recuperação/catalogação | 25 OCRs reservados no runtime | 0/25 (0%) | mediana 875,417 ms; p95 1.032,656 ms | US$ 0 |
| Modelo ajustado LoRA | mesmos 48 OCRs da linha base | 30/48 (62,50%) | média 383,841 ms | US$ 0 |
| Ajustado + recuperação/catalogação | mesmos 25 OCRs da segunda linha | 23/25 (92%) | mediana 356,361 ms; p95 496,665 ms | US$ 0 |

## Interpretação

No teste pareado de geração direta, o ajuste ganhou 47,92 pontos percentuais e
reduziu a latência média. No runtime conservador, apenas o adapter final com a
camada de recuperação passou o portão de promoção: 92%, zero falso aceite em
quatro negativos, oito recusas de segurança, receita e documento longo.

As linhas de geração direta e de runtime usam conjuntos diferentes (48 e 25
casos) e, portanto, não devem ser interpretadas como um experimento fatorial
perfeito. A tabela atende à comparação operacional dos quatro modos disponíveis,
mas não isola causalmente o efeito do RAG. Essa é uma limitação registrada, não
um resultado ocultado.

## Custo por 1.000 usos e latência RAG

A cobrança medida de API externa é **US$ 0 por 1.000 usos**, pois o processamento
é local. No ensaio RAG de 15 requests por versão, a latência média total foi
4.020,774 ms (v1) e 4.614,941 ms (v2). Com hipótese de 35 W e R$ 0,85/kWh, o
cenário energético equivale a aproximadamente **R$ 0,0332/1.000 usos** na v1 e
**R$ 0,0381/1.000 usos** na v2. Esses valores são estimativas por tempo de
parede, não leitura de wattímetro; hardware/depreciação não foram medidos.

Fontes: `finetuning/metricas.json`,
`catalog_real_active/base_runtime_independent.json`,
`catalog_real_active/candidate_runtime_independent.json` e
`rag_prompt_comparison/results.json`.
