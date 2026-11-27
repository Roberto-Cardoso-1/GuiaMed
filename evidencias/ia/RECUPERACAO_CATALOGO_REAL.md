# GuiaMed: recuperação conservadora de OCR e adapters locais

Escopo: backend Python, scripts de IA e novos artefatos/datasets. O contrato JSON de `/verificar`, `/receita`, `/alergias` e `/health` permanece igual. Nenhuma mudança Android é necessária para desserialização. Cabeçalhos HTTP informam a disponibilidade real; o launcher pode consumir `X-GuiaMed-IA`.

## Runtime

- Padrão fixo: `GuiaMed/evidencias/ia/catalog_real_active/adapter`. `LORA_ADAPTER_DIR` prevalece quando informado. Nenhum diretório experimental é procurado automaticamente.
- Manifesto obrigatório: promoção aprovada, prompt exato, revisão do modelo base, SHA-256 dos pesos, hash do catálogo e hash de avaliação que cumpre critérios de promoção. Carregamento offline exclusivo, com snapshot local existente.
- `LORA_ENABLED=false` continua desabilitando o recurso. Um override explícito `LORA_GGUF_PATH` legado resulta em incompatibilidade; não escolhe silenciosamente outro adapter. GGUF real ainda precisa de exportação e avaliação próprias.
- Estados no cabeçalho: `ready`, `disabled`, `unavailable`, `legacy-incompatible` ou `not-started`. O JSON de saúde conserva `status`, `database`, `timestamp`. A API continua com o parser se o modelo faltar/falhar; disponibilidade da API não significa disponibilidade da IA.
- O adapter fictício v2 permanece preservado. Só é carregado pelo helper de exportação/benchmark legado, com o prompt antigo na comparação; não é usado com prompt novo no runtime.

## Evidência e orçamento

Nomes exatos usam fronteiras de tokens, catálogo curado e DCB juntos. Nomes completos/sais prevalecem sobre trechos contidos; identidades distintas em uma embalagem são recusadas. A busca de recuperação combina até seis palavras e usa índice de vizinhanças de deleção, similaridade mínima 0,90 e margem 0,04 por trecho aproximado. A resposta do modelo precisa ser exclusivamente um nome do catálogo, corroborado pelo próprio OCR. O limiar antigo de aceitação 0,72 foi removido.

O documento inteiro é percorrido em janelas sobrepostas; janelas longas usam o fragmento observado no OCR para gerar, sem inserir a resposta do catálogo; 384 caracteres limitam somente uma entrada de geração, não o documento. Os limites HTTP permanecem 20 mil caracteres em embalagem e 40 mil em receita. Receitas recuperam itens pendentes mesmo quando outros já foram identificados. Quebras de linha e ponto e vírgula separam itens. Linhas candidatas recusadas, desconhecidas com dose e linhas cuja análise ficou sem orçamento continuam como barreiras para não emprestar sua dose ao item anterior.

`LORA_MAX_CALLS=4`, `LORA_BUDGET_MS=1500`: orçamento compartilhado para preparar candidatos e gerar respostas, com verificação durante a preparação e `StoppingCriteriaList` na geração. Saídas tardias são descartadas. O critério interrompe entre passos de tokens: uma operação de CPU/prefill em andamento não é preemptível. Portanto **não é deadline rígido do processo/HTTP**; a geração pode exceder pelo tempo de um passo. Não ficam threads de geração abandonadas. Um subprocesso isolado seria necessário para cancelamento rígido.

Doses, validade e alertas de alergia vêm dos parsers determinísticos; o modelo não os fornece. Validade continua exigindo rótulo explícito e usa fim do mês. Mantêm-se componentes e regras de alergias existentes.

## Reprodução local

No PowerShell, a partir de `C:/meu-sistema`, usando o cache já presente:

```powershell
.tools/finetuning-venv/Scripts/python.exe GuiaMed/scripts/run_catalog_real_experiment.py --epochs 8 --run NOVA_PASTA
.tools/finetuning-venv/Scripts/python.exe GuiaMed/scripts/evaluate_catalog_runtime.py --run NOVA_PASTA --activate
.tools/finetuning-venv/Scripts/python.exe GuiaMed/scripts/verify_ia_backend.py
```

Nomes de pasta novos são obrigatórios; artefatos existentes não são sobrescritos. A ativação recusa substituir pesos diferentes em `catalog_real_active`; revalidar os mesmos pesos atualiza apenas manifesto e evidências. O treino não promove sozinho: a avaliação independente verifica base sem adapter e candidato pelo runtime real. A promoção exige pelo menos 80% de acertos positivos, melhora sobre a base, nenhum falso aceite nos negativos medidos, receita com nomes/doses corretos, leitura longa com alergia/validade e recusa das ambiguidades medidas. Isso é um gate de engenharia neste pequeno conjunto sintético, não validação clínica.

## Limites conhecidos

Não há prova de generalização para todos os 10.777 nomes DCB nem para fotografias/manuscritos reais. O conjunto cobre 25 rótulos/aliases positivos de 14 identidades, mais abstensão. Os nomes aparecem no treino e no teste; o layout de teste é separado. Negativos e casos de segurança são pequenos e não estimam taxa de risco em produção. Cada nome tem nove exemplos positivos de treino; há apenas um exemplo negativo de treino. É preciso ampliar negativos e avaliação real.

Nomes quebrados através de linhas, várias identidades sem separador na mesma linha de receita e posologias a mais de três linhas do nome continuam limitados/recusados. Itens repetidos do mesmo nome são consolidados pela receita existente. O parser conserva sua política de datas explicitamente rotuladas. Atrasos do Android, deadlines HTTP do celular e qualidade de câmera/OCR não foram alterados; o Programador e o Maestro devem medir a integração física.

As regressões com geradores falsos são testes de controle e segurança; não são métricas do modelo. Resultados brutos, comparações, avaliação com geração real e smoke HTTP devem ser consultados nos artefatos de cada ensaio.
