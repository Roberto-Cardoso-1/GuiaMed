# Roteiro de apresentação — GuiaMed

## 0:00–0:50 — problema e V1

- Pessoa cega aponta a câmera e recebe nome, dose, validade e alerta por voz.
- O APK funciona sem internet ou computador.
- Mostrar vídeo curto do app no celular.

## 0:50–2:15 — IA local e n8n

- Mostrar `docs/arquitetura.md`.
- Explicar 24 bulas fictícias, 144 chunks, TF-IDF e resposta sempre citada.
- Abrir `evidencias/n8n/execucao_workflow_banco.txt`: três nós concluídos e
  `status_evidencia: PASSOU`.
- Mostrar `prompts/v1.md`, `v2.md` e o changelog.

## 2:15–3:10 — testes e ajuste supervisionado

- 300 exemplos: 240 treino, 60 teste e 24 classes.
- Baseline exato: 80%; classificador ajustado localmente: 100% no conjunto
  sintético de teste.
- Cinco casos RAG passaram; custo local US$ 0 e latência média abaixo de 1 ms.
- Explicar que 100% no conjunto sintético não garante desempenho em fotos reais.

## 3:10–4:20 — segurança, otimização e integração

- `rl_app_ia`: funções autorizadas, sem acesso direto às tabelas.
- RLS separa dispositivos e a visão de suporte mascara o hash.
- Mostrar a auditoria: quem pediu, operação, fontes e horário.
- Mostrar contagens de 50 mil chunks e 1 milhão de eventos fictícios.
- Mostrar os planos antes/depois de GIN e índice parcial.

## 4:20–5:00 — riscos e fechamento

- Risco principal: OCR ruim identificar medicamento incorreto.
- Mitigações: múltiplas capturas, busca conservadora, recusa e citação.
- Frase final: “Quem não enxerga não pode conferir duas vezes; o GuiaMed não
  chuta e não responde sem fonte.”

## Checklist

- [x] Integrantes e matrículas no relatório.
- [x] RAG e n8n executados localmente.
- [x] Prompts versionados.
- [x] Cinco casos com esperado e obtido.
- [x] Experimento local com baseline e métrica.
- [x] Custo e latência medidos.
- [x] Inserir no PDF os resultados finais de segurança após execução administrativa.
- [ ] Gravar vídeo curto do app físico e, opcionalmente, da execução n8n.
