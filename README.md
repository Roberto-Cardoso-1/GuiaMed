# GuiaMed V1

**Grupo GuiaMed**  
**Componentes:** João Roberto Santos Cardoso — 231070711; Gabriel de Souza Nascimento — 231071404; Daniel Queiroga Santana Martins — 231070654; Pedro Henrique Freitas Couto — 231071331; Pedro Felipe dos Anjos Santos — 232070649.  
**Disciplinas:** Projeto de Inteligência Artificial e Segurança e Otimização de BD.  
**Repositório:** https://github.com/Roberto-Cardoso-1/GuiaMed  

Aplicativo Android de acessibilidade **Zero Interface** para pessoas cegas ou com baixa visão. O app lê embalagens e receitas, registra alergias por voz e sinaliza risco por fala, vibração e painel de alto contraste. As funções principais operam no próprio aparelho, sem internet ou computador ligado.

## Interação

Com a câmera aberta, a pessoa usa os gestos em qualquer ponto da tela. O
GuiaMed orienta por voz e vibração, com texto de alto contraste para baixa visão.

| Gesto | Ação | Feedback inicial |
|---|---|---|
| 1 toque | Fotografa e identifica uma embalagem | 1 vibração curta + TTS |
| 2 toques | Fotografa e lê uma receita após a instrução falada | 2 vibrações curtas + TTS |
| 3 toques | Registra uma alergia por voz | 2 vibrações curtas; o microfone só abre após o TTS terminar |
| 4 toques | Repete as instruções e recupera um fluxo interrompido | 1 vibração curta + TTS |
| Toque longo | Abre as configurações de permissões do app | 2 vibrações curtas + TTS |

Para ler uma receita, aponte a câmera para a folha e dê **dois toques rápidos**.
Para ensaiar, imprima ou abra em outra tela a
[receita fictícia de demonstração](evidencias/receita_ficticia_demonstracao.png).
A importação de fotos e PDFs pelo menu **Compartilhar** é um recurso auxiliar
que depende da navegação no outro aplicativo.

Toda ação ou erro usa a tríade de acessibilidade: TTS em pt-BR, vibração e painel superior. Em alergia, medicamento vencido ou erro crítico, o painel fica vermelho e são emitidas três vibrações longas.

## Componentes

- Android nativo em Kotlin, CameraX, ML Kit Text Recognition, TTS/STT do Android, Retrofit e Coroutines.
- Catálogo DCB/Anvisa, parser de medicamentos, validade, receitas e alergias embarcados no Android.
- FastAPI, Pydantic, `asyncpg` e PostgreSQL opcionais para integração acadêmica.
- RAG TF-IDF local sobre 144 chunks de 24 bulas fictícias com geração local
  via `HuggingFaceTB/SmolLM2-135M-Instruct` (`backend/rag_llm.py`, decodificação
  extrativa restrita `extractive_trie`, prompts `v1`/`v2` e comparação pareada
  em `evidencias/ia/rag_prompt_comparison/`) e workflow n8n auto-hospedado
  para a evidência acadêmica, sem SaaS.
- `PdfRenderer` nativo para OCR da primeira página de PDFs compartilhados.

O parser local normaliza erros comuns de OCR, faz busca aproximada de medicamentos na base DCB da Anvisa e é conservador: não inventa dosagem nem validade. Ele reconhece rótulos de validade como `VAL`, `VTO` e carimbos compactos de fundo de caixa, por exemplo `V02 28`; uma linha de fabricação, como `F02 26`, não é tratada como validade. As alergias ficam no armazenamento privado do app e continuam disponíveis depois de fechar ou reiniciar o aparelho.

A leitura de caixas analisa os quadros mais recentes da CameraX por OCR e EAN-8/13. O índice EAN distribuído no APK deriva dos campos de produto e substância da [lista PMC publicada pela CMED/Anvisa em 09/11/2026](https://www.gov.br/anvisa/pt-br/assuntos/medicamentos/cmed/precos/arquivos/lista_pmc_20261109_111901662.xlsx/@@download/file). Foram excluídos códigos com dígito verificador inválido ou registros conflitantes. A validade e, quando há confirmação pelo OCR, a dosagem vêm do rótulo escaneado.

## Estrutura do projeto

- `app/`: aplicativo Android Kotlin, recursos XML, OCR, fala e testes.
- `backend/`: API FastAPI, parser, RAG local e integração PostgreSQL.
- `db/postgres/`: papéis, RLS, mascaramento, seeds e planos de execução.
- `data/`: bulas fictícias, conjuntos de treino/teste e catálogo local.
- `n8n/workflows/`: workflows exportados, sem segredos incorporados.
- `evidencias/`: resultados brutos de IA, segurança, integração e imagens.
- `docs/`: arquitetura, dados, ataques, segurança e limitações.
- `entrega_final/`: relatório individual de João Roberto em HTML, Markdown e PDF.

## Executar o backend opcional

Use Python 3.11 ou 3.12:

```powershell
cd backend
python -m pip install -r requirements.txt
Copy-Item .env.example .env
python main.py
```

O aplicativo executa OCR, parser e checagem de alergias no próprio aparelho. O build de integração pode consultar um modelo alinhado ao catálogo real para recuperar nomes em leituras inconclusivas. O guia de [configuração do backend](backend/README.md) descreve os artefatos compatíveis e o ambiente Python. A documentação interativa fica em `http://127.0.0.1:8000/docs`. O cabeçalho `X-GuiaMed-IA` de `/health` informa se a IA está `ready`, `disabled` ou `unavailable`; a disponibilidade da API, por si só, não comprova que o modelo carregou.

## Compilar o Android

Use JDK 17 e Android SDK 34:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

O APK é gerado em `app\build\outputs\apk\debug\app-debug.apk`.

O build **debug consulta a API por padrão** quando a leitura local precisa de complemento, mantendo o processamento local em falhas de rede. A URL padrão para o emulador é `http://10.0.2.2:8000/`. O build **release permanece offline por padrão**. A propriedade `GUIAMED_USE_REMOTE_API` permite selecionar explicitamente ambos os modos:

```powershell
# Integração no emulador
.\gradlew.bat assembleDebug -PGUIAMED_USE_REMOTE_API=true
# Operação inteiramente local, sem chamadas HTTP
.\gradlew.bat assembleDebug -PGUIAMED_USE_REMOTE_API=false
```

Em aparelho físico conectado por USB, execute `adb reverse tcp:8000 tcp:8000` e compile a variante de integração com `-PGUIAMED_API_BASE_URL=http://127.0.0.1:8000/`. Isso usa a conexão USB com o computador; não é o endereço padrão do emulador.

## Contrato da API

- `POST /verificar`: `{device_id, texto_ocr, timestamp}` → `{nome, miligramas, validade, vencido, alerta_alergia, componentes, posologia, mensagem, perigo}`
- `POST /alergias`: `{device_id, nome_alergia, timestamp}` → `{sucesso, mensagem, alergias_registradas}`
- `POST /receita`: `{device_id, texto_ocr, timestamp}` → `{medicamentos, posologia_completa, horarios, mensagem}`
- `GET /health`: estado da API e do armazenamento de alergias.
- `POST /rag/consultar`: consulta local fundamentada às bulas fictícias, com
  fontes, latência, custo e identificador de auditoria.
- `POST /rag/demo/alergia`: [demonstração local de acesso por perfil](n8n/README.md#demonstração-de-acesso-por-perfil),
  com dados fictícios e auditoria no PostgreSQL.

Os modelos Android usam `@SerializedName` e permanecem sincronizados com essas chaves `snake_case`. O corpo OCR e os nomes de alergias não são gravados em logs.

## Validação

```powershell
cd backend
python -m unittest -v test_parser test_api_contract test_local_rag test_demo_perfis
```

Os testes cobrem regressões de OCR, busca aproximada, leitura local de receitas em linhas separadas, alertas de alergia, validade vencida e carimbos compactos de validade. Para câmera, voz, vibração, persistência offline e acessibilidade, execute também o roteiro de [COMO_TESTAR.md](COMO_TESTAR.md) em aparelho físico.

Para reproduzir as evidências de IA e a comparação pareada dos prompts `v1`/`v2`:

```powershell
.\backend\.venv\Scripts\python.exe scripts\run_ai_evidence.py
..\.tools\finetuning-venv\Scripts\python.exe scripts\compare_rag_prompts.py --repeats 3 --timeout-ms 15000 --output-dir evidencias\ia\rag_prompt_comparison --power-watts 35 --brl-per-kwh 0.85
```

Os resultados ficam em [`evidencias/`](evidencias/). O workflow importável está
em [`n8n/workflows/guiamed_local_rag.json`](n8n/workflows/guiamed_local_rag.json)
e os scripts PostgreSQL executáveis estão em [`db/postgres/`](db/postgres/).

## Experimento de fine-tuning

Use Python 3.12 em um ambiente virtual próprio e instale
`scripts/requirements-finetuning.txt`. Execute
`python scripts/run_llm_finetuning.py` na raiz. O script baixa a revisão
fixada do SmolLM2, mede o original, treina LoRA e mede o ajustado.
Os [resultados preliminares](evidencias/ia/finetuning/README.md) incluem
as respostas de teste, o histórico de treino e os hashes do adaptador.
Para repetir a segunda rodada, use o
[passo a passo de treino no PowerShell](evidencias/ia/finetuning_v2/README.md#reproduzir).

## Segurança e configuração

- Não versionar `.env`, credenciais, keystores ou `local.properties`.
- Consultas PostgreSQL são parametrizadas.
- O release e o debug com `-PGUIAMED_USE_REMOTE_API=false` processam e armazenam os dados localmente. O debug integrado pode enviar o texto OCR à API configurada; não registre receitas ou alergias em logs.
- O app solicita somente câmera e microfone em tempo de execução.

## Documentação do projeto

- [Relatório final em PDF](entrega_final/Relatorio_Final_GuiaMed_Joao_Roberto.pdf) e [resumo da entrega](ENTREGA.md).
- [Relatório de limitações](docs/limitacoes.md), incluindo riscos residuais e próximos passos.
- [Índice de evidências](evidencias/README.md), com registros de execução e imagens.
- [Arquitetura](docs/arquitetura.md) e [segurança do banco](docs/seguranca.md).
- [Roteiro de testes no celular](COMO_TESTAR.md) e [testes de ataques](docs/ataques.md).

