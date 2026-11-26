# Segunda rodada de fine-tuning do SmolLM2

O treinamento continua a partir do [adaptador LoRA da primeira rodada](../finetuning/README.md).
O objetivo é recuperar o nome comercial de um medicamento fictício a partir
de texto com erros simulados de OCR.

## Resultados

| Avaliação | Adaptador anterior | Adaptador novo | Ganho |
|---|---:|---:|---:|
| Teste inédito, dois formatos de etiqueta | 2/48 (4,17%) | 23/48 (47,92%) | 43,75 p.p. |
| Teste anterior | 30/48 (62,50%) | 37/48 (77,08%) | 14,58 p.p. |

No teste inédito, o ajuste corrigiu 21 respostas e manteve os dois acertos
anteriores. Restaram 25 erros. No teste anterior, corrigiu oito respostas
e errou uma que o primeiro adaptador acertava.

Foram treinadas três épocas, com 183 atualizações em CPU, em 393,10 segundos
de treino. A primeira época foi selecionada com 13/24 acertos de validação.
As épocas seguintes tiveram 12/24 e 10/24. O adaptador selecionado resume
61 atualizações, com lote de oito e taxa de aprendizado de 0,0003.

[Métricas](metricas.json), [respostas por exemplo](respostas.json),
[histórico de treino](treino.json) e [adaptador LoRA selecionado](adapter/).

## Dados e avaliação

- [Treino](treino.jsonl) com 488 entradas das 24 classes fictícias. A rodada
  adiciona 240 exemplos com confusões de caracteres, espaços, remoções e
  trocas de letras. Quatro entradas do treino antigo foram removidas desta
  rodada por coincidirem com o teste antigo após normalizar espaços e caixa.
- [Validação](validacao.jsonl) com 24 entradas. A seleção da época usa a
  proporção de nomes exatos e, em caso de empate, a menor perda média.
- [Teste inédito](teste.jsonl) com 48 entradas em dois formatos de etiqueta
  reservados para avaliação. Os dois adaptadores recebem os mesmos textos
  e o mesmo prompt, com geração determinística e sem lista de opções.
- O teste anterior é medido novamente depois da seleção para acompanhar
  possíveis regressões. Ele pode conter sobreposição normalizada com o
  treino que gerou o primeiro adaptador, por isso é secundário.

Os dados continuam sintéticos. Os dois testes compartilham as 24 classes
do treino. Os números medem recuperação de nomes em texto e a leitura de
imagens exige uma avaliação própria.

## Reproduzir

Neste PC, abra o PowerShell e use o ambiente e o cache já preparados:

```powershell
Set-Location 'C:\meu-sistema\GuiaMed'
$env:HF_HOME = 'C:\meu-sistema\.tools\huggingface'
& 'C:\meu-sistema\.tools\finetuning-venv\Scripts\python.exe' scripts/run_llm_finetuning_v2.py --output-dir models/meu-treino
```

O script começa a partir do adaptador da primeira rodada, ajusta os parâmetros
LoRA na CPU e avalia as duas versões depois de escolher a época pela validação.
O treino anterior levou cerca de seis minutos e meio, além da avaliação.
Abra `models/meu-treino/treino.json` para acompanhar a perda por passo e o
resultado de validação ao fim de cada época. Ao terminar, `metricas.json`
compara as versões e `adapter/` contém os novos pesos. Use outra pasta de
saída se quiser guardar os resultados de mais de uma execução.

Em outro computador, crie um ambiente Python 3.12, instale as dependências de
[`scripts/requirements-finetuning.txt`](../../../scripts/requirements-finetuning.txt)
e execute `python scripts/run_llm_finetuning_v2.py --output-dir models/meu-treino`
na raiz do projeto. O primeiro download dos pesos requer conexão. Treino e
avaliação usam os dados fictícios e rodam localmente.
