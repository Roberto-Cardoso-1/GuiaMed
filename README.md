# GuiaMed V1

**Grupo GuiaMed**  
**Componentes:** João Roberto Santos Cardoso — 231070711; Gabriel de Souza Nascimento — 231071404; Daniel Queiroga Santana Martins — 231070654; Pedro Henrique Freitas Couto — 231071331; Pedro Felipe dos Anjos Santos — 232070649.  
**Disciplinas:** Projeto de Inteligência Artificial e Segurança e Otimização de BD.  

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
- RAG TF-IDF local sobre 144 chunks de 24 bulas fictícias e workflow n8n
  auto-hospedado para a evidência acadêmica, sem SaaS.
- `PdfRenderer` nativo para OCR da primeira página de PDFs compartilhados.

O parser local normaliza erros comuns de OCR, faz busca aproximada de medicamentos na base DCB da Anvisa e é conservador: não inventa dosagem nem validade. Ele reconhece rótulos de validade como `VAL`, `VTO` e carimbos compactos de fundo de caixa, por exemplo `V02 28`; uma linha de fabricação, como `F02 26`, não é tratada como validade. As alergias ficam no armazenamento privado do app e continuam disponíveis depois de fechar ou reiniciar o aparelho.

## Executar o backend opcional

Use Python 3.11 ou 3.12:

```powershell
cd backend
python -m pip install -r requirements.txt
Copy-Item .env.example .env
python main.py
```

O aplicativo não precisa desse backend para leitura, receitas, validade ou alergias. Ele é mantido para testes de integração com banco e auditoria. A documentação interativa fica em `http://127.0.0.1:8000/docs`.

## Compilar o Android

Use JDK 17 e Android SDK 34:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

O APK é gerado em `app\build\outputs\apk\debug\app-debug.apk`.

O build normal usa somente o processamento local. Para testar a integração com o backend no emulador, compile com `-PGUIAMED_USE_REMOTE_API=true`; a URL padrão de integração é `http://10.0.2.2:8000/`.

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

Para reproduzir as evidências de IA:

```powershell
.\backend\.venv\Scripts\python.exe scripts\run_ai_evidence.py
```

Os resultados ficam em [`evidencias/`](evidencias/). O workflow importável está
em [`n8n/workflows/guiamed_local_rag.json`](n8n/workflows/guiamed_local_rag.json)
e os scripts PostgreSQL executáveis estão em [`db/postgres/`](db/postgres/).

## Segurança e configuração

- Não versionar `.env`, credenciais, keystores ou `local.properties`.
- Consultas PostgreSQL são parametrizadas.
- O build padrão processa e armazena os dados localmente; a API remota só é ativada por propriedade de compilação em testes de integração.
- O app solicita somente câmera e microfone em tempo de execução.

## Documentação do projeto

- [Relatório em PDF](entrega_17_11/Relatorio_Andamento_17_11.pdf) e [resumo da entrega](ENTREGA.md).
- [Índice de evidências](evidencias/README.md), com registros de execução e imagens.
- [Arquitetura](docs/arquitetura.md) e [segurança do banco](docs/seguranca.md).
- [Roteiro de testes no celular](COMO_TESTAR.md) e [roteiro de apresentação](docs/apresentacao.md).

