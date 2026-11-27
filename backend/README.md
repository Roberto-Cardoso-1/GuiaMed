# Runtime local do GuiaMed

A API usa o parser e um LoRA alinhado a nomes reais, carregado uma vez por processo em FP32. O modelo opera sobre texto OCR; não reconhece imagens nem transforma o ML Kit em leitor de manuscritos.

## Iniciar e conferir a IA

Instale `requirements-lora.txt` no ambiente Python 3.12 usado para iniciar a API. Neste workspace, o ambiente pronto está em `C:/meu-sistema/.tools/finetuning-venv/`. A revisão da base é `12fd25f77366fa6b3b4b768ec3050bf629380bac`; o carregador usa somente o cache local em `HF_HOME/hub/`, ou `.tools/huggingface/hub/` por padrão.

O adapter padrão é `../evidencias/ia/catalog_real_active/adapter/`. `LORA_ADAPTER_DIR` permite um override explícito. O carregador exige manifesto aprovado, prompt/revisão compatíveis e SHA-256 dos pesos, catálogo e avaliação. Se arquivos ou dependências faltarem, a API continua com o parser e informa a indisponibilidade.

```powershell
# Na pasta backend, com o ambiente correto ativado
python -m uvicorn main:app --host 0.0.0.0 --port 8000
$resposta = Invoke-WebRequest http://127.0.0.1:8000/health -UseBasicParsing
$resposta.Headers['X-GuiaMed-IA']
```

`ready` confirma que o modelo carregou; `disabled`, `unavailable` e `legacy-incompatible` indicam operação sem esse complemento. HTTP 200 sozinho não comprova uso do LoRA. No Windows deste workspace, `C:/meu-sistema/Iniciar-GuiaMed.ps1` inicia banco/API e também informa esse estado.

- `LORA_ENABLED=false` desativa o modelo.
- `LORA_THREADS=4` controla threads de CPU.
- `LORA_MAX_CALLS=4` limita gerações por documento.
- `LORA_BUDGET_MS=1500` limita cooperativamente preparação e inferência. A geração para entre tokens e descarta respostas tardias; um passo de CPU em andamento pode ultrapassar o prazo. O teste com orçamento de 1 ms levou cerca de 140 ms e descartou a resposta.

## Recuperação e segurança

`/verificar` examina o documento inteiro para procurar ambiguidades e envia somente trechos OCR relevantes ao modelo. Documentos acima de 768 caracteres não são descartados inteiramente. Um nome proposto exige evidência independente no OCR e no catálogo; nomes fictícios, divergentes e textos de instrução hostil são recusados.

`/receita` também tenta recuperar itens pendentes quando outros já foram reconhecidos, dentro do mesmo orçamento. Dose, frequência, duração e validade vêm do texto impresso; a geração não fornece esses campos. A fronteira de um item recusado impede atribuir sua dose ao anterior. Alergias continuam sendo cruzadas com os componentes catalogados.

O JSON de `/verificar`, `/receita` e `/alergias` foi preservado. O estado da IA é exposto no cabeçalho `X-GuiaMed-IA`, sem registrar o texto das receitas ou alergias.

## Evidência do modelo ativo

O treino usou oito épocas, 226 exemplos sintéticos com nomes reais, 25 aliases de 14 medicamentos e layout de teste reservado. A avaliação não mede nomes inéditos, todo o catálogo DCB, fotos reais ou desempenho da câmera.

| Fluxo no mesmo conjunto | Acertos positivos | Falsos aceites nos 4 negativos | Mediana / p95 |
| --- | ---: | ---: | ---: |
| Base sem adapter + runtime | 0/25 | 0/4 | 875 / 1033 ms |
| Adapter real + runtime | 23/25 | 0/4 | 356 / 497 ms |

A receita de três itens levou cerca de 769 ms nesse computador. Essas medidas não são o tempo total do Android. Os dois positivos ainda recusados não foram aceitos por redução de limiar.

Veja [resultados e limites](../evidencias/ia/RECUPERACAO_CATALOGO_REAL.md), [avaliação ativa](../evidencias/ia/catalog_real_active/candidate_runtime_independent.json) e [smoke HTTP](../evidencias/ia/catalog_real_active/http_smoke.json).

## Reproduzir

A partir da raiz `GuiaMed/`, usando o Python do ambiente de fine-tuning:

```powershell
python -m unittest discover -s backend -p 'test_*.py'
python scripts/smoke_catalog_api.py
python scripts/run_catalog_real_experiment.py --run catalog_real_novo --epochs 8
python scripts/evaluate_catalog_runtime.py --run catalog_real_novo
```

Use nomes de execução novos; o treino não sobrescreve os anteriores. A promoção exige avaliação real do runtime e ativação explícita. O destino ativo existente não é substituído silenciosamente.

## Histórico v2 e GGUF

Os artefatos v2 e seus GGUF foram preservados para reprodução acadêmica. Eles foram treinados com nomes fictícios e não recebem o novo prompt no runtime. `LORA_GGUF_PATH` legado é recusado como incompatível, sem fallback oculto. Um GGUF do modelo real ainda precisa ser exportado e avaliado antes de uso. O inicializador não força mais o F16 v2. A [avaliação v2](../evidencias/ia/finetuning_v2/README.md) permanece como registro histórico.
