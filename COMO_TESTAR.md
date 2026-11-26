# Como testar o GuiaMed V1

## Preparação

- JDK 17 e Android SDK 34.
- Aparelho físico para validar câmera, TTS, STT e vibração. O computador e a internet podem permanecer desligados durante o teste funcional.
- Backend FastAPI somente para o teste opcional de integração com banco.
- Não é necessário baixar `por.traineddata`: o OCR usa ML Kit.

Para validar o código antes do teste mobile:

```powershell
cd backend
python -m unittest -v test_parser.py
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

### Interface com fonte ampliada

1. Aumente o tamanho da fonte nos ajustes do Android e volte ao GuiaMed. Confira se o resultado e a legenda dos quatro gestos quebram em linhas e continuam legíveis.
2. Confirme que os cantos amarelos aparecem quando a imagem da câmera está ativa. Faça os gestos na câmera, no painel de status e na legenda inferior.
3. Provoque um alerta de alergia com o dado de teste. Confira o texto “Alerta de alergia” no painel vermelho, o aviso falado e as três vibrações longas.
4. No tamanho máximo de fonte, leia uma receita de teste com vários medicamentos, pela câmera e por PDF. Confira se o painel exibe os nomes, as doses, a frequência e os demais campos falados. Role até o final e volte ao início. Na câmera, a rolagem dos detalhes não deve iniciar outra leitura.
5. Leia uma embalagem com validade e compare os dados falados com os exibidos. Depois teste uma embalagem vencida e uma cadastrada como alergia para conferir os rótulos “Medicamento vencido” e “Alerta de alergia”.
6. Faça o cadastro de alergia e confira se a confirmação mostra o nome que o app registrou.

## Regressões relatadas

### Receita impressa que ficava em captura

1. Em modo avião, enquadre uma receita impressa de teste e dê dois toques rápidos. Confirme que o painel passa de “Capturando receita” para “Lendo texto da receita” antes de anunciar o resultado.
2. Repita com uma amostra que tenha um medicamento legível e campos ausentes. Confira se o app fala os dados encontrados e indica o que não identificou.
3. Repita com a lente coberta. Confira se o app anuncia a falha ou orienta uma nova tentativa e volta a aceitar os gestos ao terminar.

### Lanterna ao mudar a iluminação

1. Abra a câmera do app e faça sombra sobre uma caixa ou receita. Antes do gesto, a lanterna deve permanecer apagada.
2. Ainda na sombra, inicie a leitura da caixa com um toque ou da receita com dois. Em um aparelho com lanterna, ela deve acender em pouca luz e permanecer acesa durante a leitura, mesmo ao sair da sombra ou virar a caixa para ler a validade.
3. Confira se a lanterna apaga ao terminar, cancelar a busca de validade ou sair do app. Repita em boa luz e confira se a leitura ocorre com a lanterna apagada.

Use caixas reais, com boa luz, texto preenchendo a maior parte do enquadramento e sem reflexo.

| Cenário | Resultado esperado |
|---|---|
| Dipirona / `DIPIR0NA 5OO MG` | Identifica Dipirona Sódica e fala 500 mg |
| Nimesulida com pequena falha, como `NIMESULLDA` | Identifica Nimesulida por busca aproximada |
| `IBUPROFENO 600 MG` | Fala 600 mg; nunca substitui por 250 mg |
| Embalagem sem validade visível | Fala que a validade não foi encontrada; não inventa ano/data |
| `VAL 10/2026` em novembro de 2026 | Marca vencido, painel vermelho e três vibrações longas |
| `V02 28` somente na lateral, com `FAB 02/26` | Mantém o nome lido na frente e mostra e fala 02/2028 |
| `Válido até 17/11/2026` em 18/11/2026 | Preserva o dia impresso e anuncia que venceu |
| Somente `FAB 02/26`, data sem rótulo ou duas leituras divergentes | Nova tentativa ou “Validade não encontrada” |

## Gestos

### Um toque — embalagem

1. Aponte a câmera e toque uma vez.
2. Deve ocorrer uma vibração curta e “Lendo embalagem”.
3. O resultado deve ser falado com nome e apenas os dados realmente identificados.
4. Falha de OCR ou rede deve produzir painel vermelho, TTS e três vibrações longas.
5. Se o nome for encontrado sem alerta e sem validade, aguarde a instrução
   e vire a **mesma embalagem**. Mostre a linha da validade mantendo o
   celular parado. O nome da frente deve permanecer junto da data da lateral.
6. Repita tocando uma vez durante a busca para cancelar. Repita também
   colocando o app em segundo plano durante a busca. Ao voltar, uma nova
   leitura deve funcionar sem receber resultado da foto anterior.

### Primeira leitura e logs ao vivo

1. Feche o app pelo sistema e reabra. Assim que a prévia aparecer, enquadre uma caixa e dê um toque. Confira o aviso curto “Lendo”, a vibração e o resultado. Repita de frente e com apenas o código EAN visível.
2. Cadastre uma alergia de teste correspondente a uma substância da embalagem. Repita com texto e EAN. O alerta deve ser falado antes do nome e dos demais dados e o painel deve ficar vermelho.
3. Com o TalkBack ativo, foque o título do painel e use o menu de ações do leitor para ler embalagem, ler receita ou acessar os demais comandos. Compare com os gestos e comandos de voz.
4. Com depuração USB autorizada, acompanhe o tempo e a rota de leitura em outro terminal.

```powershell
adb devices
adb logcat -v time -s GuiaMedScan:D OcrEngine:W GuiaMed:W
```

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

### Funcionamento sem computador

1. Feche o backend e desligue o Wi-Fi e os dados móveis do celular.
2. Abra o GuiaMed e cadastre “Dipirona” com três toques.
3. Leia uma embalagem de Dipirona ou Novalgina com um toque: deve haver alerta de alergia.
4. Feche o app completamente, abra novamente e repita a leitura: o alerta deve continuar ativo.
5. Leia uma receita com dois toques e confirme que os dados são processados sem mensagem de conexão com servidor.

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


### Troca de função durante uma ação

1. Inicie a leitura de embalagem com 1 toque. Durante o foco, OCR e fala do resultado, dê 3 toques: a leitura deve parar e iniciar o cadastro de alergia, com painel e duas vibrações curtas. O microfone só deve abrir depois da instrução falada.
2. Durante o cadastro (instrução, escuta e confirmação), dê 2 toques: deve iniciar a leitura de receita sem falas ou resultados atrasados da alergia.
3. Durante a captura e OCR da receita, dê 1 toque: deve iniciar a embalagem sem resultados ou erros atrasados da receita. Repita com uma imagem compartilhada.
4. Troque várias vezes entre 1, 2 e 3 toques, inclusive durante a fala do resultado. Confirme que somente a última função continua e que a lanterna não permanece acesa no cadastro por voz.
5. Confira que 4 toques ainda encerram a operação e que 1 toque após identificar a embalagem mantém a opção de concluir sem validade. A troca respeita a janela de 500 ms para reconhecer o gesto.
