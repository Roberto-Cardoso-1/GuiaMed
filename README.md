# GuiaMed V1

Aplicativo Android de acessibilidade **Zero Interface** para pessoas cegas ou com baixa visão. O app lê embalagens e receitas, registra alergias por voz e sinaliza risco por fala, vibração e painel de alto contraste.

## Interação

Não há botões, menus ou caixas de diálogo próprias do app.

| Gesto | Ação | Feedback inicial |
|---|---|---|
| 1 toque | Fotografa e identifica uma embalagem | 1 vibração curta + TTS |
| 2 toques | Fotografa e lê uma receita após a instrução falada | 2 vibrações curtas + TTS |
| 3 toques | Registra uma alergia por voz | 2 vibrações curtas; o microfone só abre após o TTS terminar |
| 4 toques | Repete as instruções e recupera um fluxo interrompido | 1 vibração curta + TTS |
| Toque longo | Abre as configurações de permissões do app | 2 vibrações curtas + TTS |

Fotos e PDFs também podem ser enviados ao GuiaMed pelo menu **Compartilhar** de outro aplicativo. Não é necessária permissão de armazenamento.

Toda ação ou erro usa a tríade de acessibilidade: TTS em pt-BR, vibração e painel superior. Em alergia, medicamento vencido ou erro crítico, o painel fica vermelho e são emitidas três vibrações longas.

## Componentes

- Android nativo em Kotlin, CameraX, ML Kit Text Recognition, TTS/STT do Android, Retrofit e Coroutines.
- FastAPI, Pydantic, `asyncpg` e PostgreSQL opcional.
- `PdfRenderer` nativo para OCR da primeira página de PDFs compartilhados.

O parser do backend normaliza erros comuns de OCR, faz busca aproximada de medicamentos e é conservador: não inventa dosagem nem validade. Uma data só é tratada como validade quando aparece junto de `validade`, `val`, `vencimento` ou `venc`.

## Executar o backend

Use Python 3.11 ou 3.12:

```powershell
cd C:\meu-sistema\GuiaMed\backend
python -m pip install -r requirements.txt
Copy-Item .env.example .env
python main.py
```

`DATABASE_URL` é opcional durante desenvolvimento. Sem PostgreSQL, as alergias ficam em memória até o backend reiniciar. A documentação interativa fica em `http://127.0.0.1:8000/docs`.

## Compilar o Android

Use JDK 17 e Android SDK 34:

```powershell
cd C:\meu-sistema\GuiaMed
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

O APK é gerado em `app\build\outputs\apk\debug\app-debug.apk`.

A URL local está definida, conforme o contrato do projeto, como `http://10.0.2.2:8000/`. Ela funciona no emulador Android com o backend rodando no computador. O tráfego HTTP é permitido somente para esse endereço; os demais destinos continuam exigindo HTTPS.

## Contrato da API

- `POST /verificar`: `{device_id, texto_ocr, timestamp}` → `{nome, miligramas, validade, vencido, alerta_alergia, componentes, posologia, mensagem, perigo}`
- `POST /alergias`: `{device_id, nome_alergia, timestamp}` → `{sucesso, mensagem, alergias_registradas}`
- `POST /receita`: `{device_id, texto_ocr, timestamp}` → `{medicamentos, posologia_completa, horarios, mensagem}`
- `GET /health`: estado da API e do armazenamento de alergias.

Os modelos Android usam `@SerializedName` e permanecem sincronizados com essas chaves `snake_case`. O corpo OCR e os nomes de alergias não são gravados em logs.

## Validação

```powershell
cd C:\meu-sistema\GuiaMed\backend
python -m unittest -v test_parser.py
```

Os testes cobrem as regressões de Dipirona com erros de OCR, Nimesulida aproximada, Ibuprofeno 600 mg, ausência de validade, validade vencida e alerta de alergia. Para câmera, voz, vibração e acessibilidade, execute também o roteiro de [COMO_TESTAR.md](COMO_TESTAR.md) em aparelho físico.

## Segurança e configuração

- Não versionar `.env`, credenciais, keystores ou `local.properties`.
- Consultas PostgreSQL são parametrizadas.
- O modo mock existe apenas via `GuiaMedRepository.createWithMock()` para testes explícitos; a aplicação V1 usa a API real.
- O app solicita somente câmera e microfone em tempo de execução.

