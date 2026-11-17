# Fine-tuning local do SmolLM2

Executado em 17/11/2026, com dados fictícios do GuiaMed. A tarefa é gerar
somente o nome comercial presente em um texto com erros sintéticos de OCR.

O modelo pré-treinado é
[HuggingFaceTB/SmolLM2-135M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct),
revisão `12fd25f77366fa6b3b4b768ec3050bf629380bac`.
O treino ajustou 460.800 parâmetros LoRA nas projeções `q_proj` e `v_proj`.
Foram executadas 3 épocas, 189 atualizações, com lote 4, learning rate 0,001,
rank 8, alpha 16 e dropout 0,05.

## Comparação preliminar

| Medida | Original | Após fine-tuning |
|---|---:|---:|
| Acertos exatos | 7/48 | 30/48 |
| Acurácia | 14,58% | 62,50% |
| Perda média nos tokens da resposta | 7,2616 | 0,4388 |
| Tempo médio por exemplo | 866,97 ms | 383,84 ms |

O ganho de acurácia foi de 47,92 pontos percentuais. A avaliação usa geração
livre determinística com limite de 16 tokens, o mesmo prompt e as mesmas
48 entradas em ambas as versões. A medição de tempo inclui geração e cálculo
de perda. O critério de acerto compara o nome completo, ignorando maiúsculas.

Treino em CPU com quatro threads, duração de 172,50 segundos. Custo de API
igual a US$ 0. O consumo elétrico local não foi medido.

## Dados e arquivos

- 300 exemplos sintéticos, derivados de 24 bulas fictícias.
- [Treino](../../../data/ft/llm_train.jsonl) com 252 exemplos.
- [Teste](../../../data/ft/llm_test.jsonl) com 48 exemplos, dois por classe.
- [Respostas do original](base.json) e [respostas do ajustado](ajustado.json).
- [Histórico das 189 atualizações](training_log.json).
- [Parâmetros, resultados e hashes SHA-256](metricas.json).
- Adaptador treinado em [`adapter/`](adapter/), com configuração e pesos.

O script verifica ausência de texto idêntico entre os dois conjuntos. Ambos
compartilham as classes e a família de templates, de modo que essa medição
tem alcance limitado. Os 18 erros do modelo ajustado estão preservados.
Este experimento mede identificação em texto sintético. A leitura pela câmera
exige avaliação física própria.

## Reproduzir

Na raiz do projeto, com Python 3.12 e ambiente virtual próprio, execute
`python -m pip install -r scripts/requirements-finetuning.txt` e
`python scripts/run_llm_finetuning.py`.
O download inicial requer internet. O treino usa o computador local.
O script salva o checkpoint completo em `models/ocr-smollm2-lora/`.
O adaptador publicado pode ser recarregado com `PeftModel.from_pretrained`
sobre a revisão do modelo base indicada acima.
