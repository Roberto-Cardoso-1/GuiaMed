# Como testar o GuiaMed V1

## Preparação

- JDK 17 e Android SDK 34.
- Backend FastAPI rodando na porta 8000.
- Emulador Android para a integração local com `10.0.2.2`, ou um aparelho físico para validar câmera, TTS, STT e vibração com a conectividade do ambiente devidamente preparada.
- Não é necessário baixar `por.traineddata`: o OCR usa ML Kit.

Antes do teste mobile:

```powershell
cd backend
python -m unittest -v test_parser.py
python main.py
```

Em outro terminal:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat installDebug
```

## Primeira execução

1. Abra o GuiaMed.
2. Para cada permissão, confirme que o painel, uma vibração curta e o TTS explicam o pedido antes do diálogo do Android.
3. Conceda câmera e microfone.
4. Confirme o anúncio inicial e o painel preto com texto amarelo.
5. Negue uma permissão em uma execução separada: deve haver painel vermelho, três vibrações longas e aviso falado. O toque longo deve abrir as configurações do app.

## Regressões relatadas

Use caixas reais, com boa luz, texto preenchendo a maior parte do enquadramento e sem reflexo.

| Cenário | Resultado esperado |
|---|---|
| Dipirona / `DIPIR0NA 5OO MG` | Identifica Dipirona Sódica e fala 500 mg |
| Nimesulida com pequena falha, como `NIMESULLDA` | Identifica Nimesulida por busca aproximada |
| `IBUPROFENO 600 MG` | Fala 600 mg; nunca substitui por 250 mg |
| Embalagem sem validade visível | Fala que a validade não foi encontrada; não inventa ano/data |
| `VAL 10/2026` em novembro de 2026 | Marca vencido, painel vermelho e três vibrações longas |

## Gestos

### Um toque — embalagem

1. Aponte a câmera e toque uma vez.
2. Deve ocorrer uma vibração curta e “Lendo embalagem”.
3. O resultado deve ser falado com nome e apenas os dados realmente identificados.
4. Falha de OCR ou rede deve produzir painel vermelho, TTS e três vibrações longas.

### Dois toques — receita

1. Aponte para a receita e toque duas vezes em até 500 ms.
2. O app vibra duas vezes e fala a instrução completa.
3. A foto só pode ser capturada depois que o TTS terminar; nenhum diálogo visual deve aparecer.
4. Confirme nome, dosagem, frequência, duração, instruções e horários quando presentes.

Para imagem/PDF do WhatsApp ou outro app, use **Compartilhar → GuiaMed**. A primeira página de PDF é renderizada e processada sem pedir acesso geral aos arquivos.

### Três toques — alergia por voz

1. Toque três vezes em até 500 ms.
2. Aguarde o fim de “Diga o nome...”. O microfone não deve abrir enquanto o TTS fala.
3. Ao ouvir duas vibrações e ver “Pode falar agora”, diga “Dipirona”. Há até 20 segundos para concluir.
4. Aguarde a confirmação falada.
5. Leia Dipirona com um toque: deve haver alerta de alergia, painel vermelho e três vibrações longas.
6. Repita o cadastro e a leitura várias vezes. O app não pode ficar silencioso nem travado após uma sessão anterior.

### Quatro toques e toque longo

- Quatro toques devem cancelar estado travado, parar uma escuta pendente e repetir as instruções.
- Toque longo deve anunciar e abrir as configurações do aplicativo.

## Lanterna e ciclo de vida

- Em baixa luz, a lanterna deve ligar automaticamente; em boa luz, desligar.
- Envie o app ao segundo plano durante uma escuta e volte. Ele deve aceitar novos gestos sem manter o microfone preso.
- Gire ou bloqueie/desbloqueie o aparelho e confirme que não há fala concorrente ou travamento.

## Comandos úteis

```powershell
adb logcat -s GuiaMed TtsManager SpeechRecognition OcrEngine
adb shell dumpsys package com.guiamed | findstr permission
```

Os logs podem registrar falhas técnicas e contagem de caracteres, mas não devem conter o texto OCR, receitas ou alergias.

