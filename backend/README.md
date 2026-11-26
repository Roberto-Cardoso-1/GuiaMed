# Runtime local do GuiaMed

O startup prepara o índice RAG de `../data/bulas.json` e carrega uma instância do LoRA v2 por processo do servidor. O modo padrão carrega a base e mescla o adapter em FP32. Para rodar nesse modo, instale `requirements-lora.txt` no mesmo ambiente Python 3.12 que inicia o backend. Com múltiplos workers, cada processo usa sua própria cópia do modelo.

- A base é o SmolLM2-135M-Instruct na revisão `12fd25f77366fa6b3b4b768ec3050bf629380bac`. O runtime procura em `HF_HOME/hub/models--HuggingFaceTB--SmolLM2-135M-Instruct/snapshots/<revisão>`. Sem `HF_HOME`, usa `.tools/huggingface` na raiz do workspace.
- O adapter é lido de `../evidencias/ia/finetuning_v2/adapter/` e verificado por SHA-256. O carregador usa exclusivamente arquivos locais.
- `LORA_ENABLED=false` desativa o carregamento. `LORA_THREADS` controla as threads de CPU. Se faltarem arquivos ou dependências, a API registra a falha de inicialização e atende com o parser.
- O `POST /verificar` consulta o LoRA somente quando o parser não identifica o medicamento e o OCR contém uma única evidência corroborável no catálogo. Essa evidência corresponde ao nome ou alias completo com espaços de OCR. O modelo pode confirmar o nome, e o parser extrai dose e validade do OCR e cruza componentes do catálogo com as alergias cadastradas. Solicitações com modelo ocupado ou indisponível seguem com o resultado do parser. O `POST /receita` também pode confirmar um nome quando o parser não reconhece nenhum item, com no máximo uma geração por receita. O adapter foi treinado com nomes fictícios, então seu desempenho em embalagens reais permanece por avaliar.

## Exportar e selecionar GGUF

Execute em `GuiaMed/backend`, no ambiente de fine-tuning. A exportação requer o conversor do llama.cpp com suas dependências já instaladas e o binário local `llama-quantize`. O checkpoint mesclado é intermediário. O exportador aceita `--quantization Q4_K_M` (padrão), `Q8_0` ou `F16` e publica o GGUF no destino em um sistema de arquivos com suporte a hard links, como NTFS. O exportador recusa sobrescrever uma saída existente.

```powershell
python -B export_lora_gguf.py --converter C:/tools/llama.cpp/convert_hf_to_gguf.py `
    --quantizer C:/tools/llama.cpp/bin/llama-quantize.exe `
    --output ../models/guiamed-lora-v2-q4_k_m.gguf
```

Se o conversor usa outro ambiente Python, informe `--converter-python`. Para carregar a saída, instale `llama-cpp-python` no ambiente do backend e configure `LORA_GGUF_PATH` com o caminho do GGUF exportado. Neste Windows x64 com Python 3.12, a versão validada foi `0.3.18` do [índice de binários CPU do projeto](https://abetlen.github.io/llama-cpp-python/whl/cpu). Um caminho relativo é resolvido a partir do diretório de execução do backend. Compare o GGUF com o FP32 usando os mesmos textos e prompt antes de habilitar o arquivo exportado. Se o GGUF configurado não carregar, a API tenta o LoRA v2 em FP32 no mesmo startup.


## Variante validada no GuiaMed

O inicializador `Iniciar-GuiaMed.ps1` seleciona o GGUF F16 gerado em `models/guiamed-lora-v2-f16.gguf` quando disponível e quando o ambiente e o `.env` permitem usar o caminho padrão. A seleção foi feita a partir dos 48 exemplos sintéticos inéditos do LoRA v2, com geração determinística e quatro threads.

| Runtime | Acertos | Mediana de inferência |
| --- | ---: | ---: |
| FP32 mesclado | 23/48 | 606 ms |
| GGUF F16 | 23/48 | 242 ms |
| GGUF Q4_K_M | 20/48 | 247 ms |
| GGUF Q8_0 | 21/48 | 328 ms |

O resultado mede a recuperação de nomes sintéticos. A leitura de embalagens reais e o tempo de ponta a ponta do Android exigem teste com câmera.
