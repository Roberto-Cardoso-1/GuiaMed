# Relatório de andamento — GuiaMed

**Grupo:** GuiaMed  
**Componentes:** João Roberto Santos Cardoso — 231070711; Gabriel de Souza Nascimento — 231071404; Daniel Queiroga Santana Martins — 231070654.  
**Disciplinas:** Projeto de Inteligência Artificial; Segurança e Otimização de BD.  
**Repositório:** <https://github.com/Roberto-Cardoso-1/GuiaMed>

## Parte comum — andamento

O GuiaMed é um aplicativo Android nativo de acessibilidade para pessoas cegas ou com baixa visão. A interação é feita por gestos sobre a própria câmera, com resposta simultânea por voz, vibração e painel de alto contraste. Um toque lê uma embalagem, dois toques leem uma receita, três toques registram alergia por voz e quatro repetem as instruções.

Já funciona a captura pela câmera com foco automático, OCR local por ML Kit, leitura de embalagens, receitas e PDFs/imagens compartilhados, reconhecimento de princípios ativos e nomes comerciais, identificação de dosagem, alerta de alergia e aviso de medicamento vencido. A identificação de princípios ativos usa um catálogo local com 10.777 denominações da DCB/Anvisa, sem consulta a serviço externo. O OCR trata erros frequentes, como `0/O`, e a leitura de validade reconhece tanto formatos convencionais quanto carimbos compactos de fundo de caixa, como `V02 28` (fevereiro de 2028); `F02 26` é corretamente entendido como fabricação, não como validade.

Também foi implantada uma leitura guiada de embalagem: após reconhecer o medicamento, o app informa que virar a caixa para procurar a validade é opcional. Se a pessoa quiser continuar, o app faz novas leituras; se preferir encerrar, um toque retorna apenas o medicamento identificado. Para receitas, a V1 usa captura da folha inteira, uma passagem normal e outra de alto contraste e até três tentativas quando faltam informações. O parser une linhas próximas, evitando atribuir a dosagem de um medicamento ao seguinte.

Desde 27/10, a arquitetura passou de materiais de prototipação/documentação para uma V1 executável: Android Kotlin com CameraX e ML Kit no dispositivo; API FastAPI com Pydantic; cliente Retrofit; e PostgreSQL opcional para alergias. A identificação não depende de SaaS nem de site: o OCR e o catálogo DCB ficam locais; a API pode ser executada no computador de demonstração. O código mantém um contrato JSON explícito entre o aplicativo e o backend e não registra textos de OCR ou alergias em logs da aplicação.

Os principais riscos restantes são: OCR de caligrafia médica, reflexo ou desfoque na caixa; indisponibilidade do servidor local quando o telefone não está conectado à rede/USB de demonstração; e interpretação inadequada de informação de saúde. As mitigações são múltiplas capturas com foco, recusa conservadora quando a confiança é baixa, alerta audível de incerteza, dados fictícios/anonimizados nos testes e orientação para confirmar receita, posologia e uso com profissional de saúde.

## Parte de IA

A IA usada nesta V1 é o reconhecimento de texto por aprendizado de máquina do ML Kit, executado no próprio Android, combinado a normalização de OCR e busca aproximada contra o catálogo local DCB. O sistema não usa LLM, API de IA, SaaS ou dados clínicos reais. A entrada é a imagem da embalagem/receita; a saída é texto estruturado e conservador para nome, dosagem, validade e itens de receita. O principal teste de qualidade é a suíte de regressão do backend, que cobre OCR ruidoso, medicamentos fora do catálogo manual, receitas em várias linhas e datas compactas de validade.

## Parte de Segurança e integração

O modelo de segurança entregue em `db/` usa dados fictícios e separa catálogo público de dados de saúde (alergias, receitas e logs). O perfil `rl_app_ia` recebe somente `EXECUTE` em procedures parametrizadas e tem `DENY` para leitura/escrita direta no schema; por isso, nem uma camada de IA/API pode selecionar alergias ou receitas livremente. A prova reproduzível está em [EVIDENCIAS_SEGURANCA_INTEGRACAO.md](EVIDENCIAS_SEGURANCA_INTEGRACAO.md): tentativa de acesso direto negada, consulta controlada por procedure e registro de auditoria com quem solicitou, o que foi consultado e quando.

## Contribuição individual — João Roberto Santos Cardoso

Implementei e integrei a V1 Android/FastAPI do GuiaMed. Trabalhei na captura por CameraX, OCR local, acessibilidade por TTS/vibração e comunicação com a API. Ampliei o reconhecimento para a base DCB/Anvisa, melhorei a leitura de receitas e de validade em carimbos compactos. Também criei testes de regressão, gerei builds Android e documentei o roteiro de segurança e integração.
