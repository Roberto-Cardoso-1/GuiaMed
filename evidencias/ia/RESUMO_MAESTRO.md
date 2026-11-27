# Entrega ao Maestro — IA e backend GuiaMed

## Validação consolidada pelo Maestro

**Atualização da continuação:** corrigidas fronteiras de medicamentos e associação de dose em ambos os parsers. Validação atual: **72 testes backend + 64 Android**, modelo revalidado sem retreino e API real reiniciada. Três APKs atualizados. [Resultados e limitação do emulador](continuacao_blocos/README.md).

- Backend final: **56 testes aprovados**. Android: **49 testes aprovados**, build e três variantes de APK gerados.
- Inicializador atualizado e executado: PostgreSQL conectado e API em `127.0.0.1:8000` com `X-GuiaMed-IA: ready`.
- `/verificar` e `/receita` também foram validados por HTTP real nesse servidor, com dados sintéticos e sem cadastro de dados no banco. Evidência em [`live_http_smoke.json`](catalog_real_active/live_http_smoke.json).
- [APKs e instruções](../../app/build/outputs/guiamed/LEIA-ME.md): emulador integrado, aparelho USB integrado e offline, com SHA-256. O modelo continua no backend.
- `backend/README.md`, `.env.example`, README principal e `COMO_TESTAR.md` foram sincronizados. Nenhum aparelho estava conectado; câmera, voz, vibração e latência total no dispositivo permanecem para teste físico.

Implementação concluída com adapter real aprovado e ativado em `C:/meu-sistema/GuiaMed/evidencias/ia/catalog_real_active/adapter`. `LORA_ADAPTER_DIR` explícito prevalece sobre o padrão. Não há varredura de experimentos nem promoção automática de qualquer pasta. Overrides explícitos de GGUF legado são recusados como incompatíveis, sem fallback oculto para outra linhagem.

O contrato JSON foi preservado. Saúde verificada por HTTP com startup e modelo reais: `X-GuiaMed-IA: ready`. PostgreSQL não estava configurado nesse smoke: alergias foram registradas em memória, sem persistência. Não foram alterados Android, README/COMO_TESTAR da raiz, configuração privada, inicializador ou Git. Não houve instalação de pacotes nem download de pesos.

## Arquivos de implementação

Alterados:
- `GuiaMed/backend/main.py`: identificação conservadora unificada entre curados/DCB, recusa de ambiguidades, recuperação por trechos e orçamento, múltiplos itens pendentes na receita, proteção contra dose de outro item e cabeçalhos de disponibilidade.
- `GuiaMed/backend/local_lora.py`: prompt real, manifesto/revisão/hash/evidências obrigatórios, v2 bloqueado no runtime, adapter padrão fixo, geração com cancelamento entre tokens.
- `GuiaMed/backend/test_local_lora.py`: teste legado ajustado para exigir recusa de GGUF incompatível.

Novos:
- `GuiaMed/backend/test_recovery.py`: regressões de segurança, preparação, prazo, documentos longos, recuperação, doses e estado do modelo.
- `GuiaMed/scripts/run_catalog_real_experiment.py`: treino offline com cobertura integral por época, splits e baseline base/v2.
- `GuiaMed/scripts/evaluate_catalog_runtime.py`: avaliação independente com geração real, portão e promoção explícita.
- `GuiaMed/scripts/smoke_catalog_api.py`: saúde e rotas sem mocks, incluindo alergia/validade/doses e prova de cancelamento cooperativo.
- `GuiaMed/scripts/verify_ia_backend.py`: execução reprodutível dos testes, com log UTF-8.
- `GuiaMed/data/ft/catalog_real_20261126/` e `catalog_real_coverage_20261126/`: novos splits/manifestações de dataset; os datasets antigos permanecem preservados.
- `GuiaMed/evidencias/ia/catalog_real_20261126/`, `catalog_real_coverage_20261126/`, `catalog_real_active/`, `backend_recovery_validation/`, este resumo e `RECUPERACAO_CATALOGO_REAL.md`.

## Resultados

O primeiro ensaio de 12 passos aleatórios não melhorou: 0/25 positivos. Foi mantido como evidência de fracasso e não é usado no runtime.

O ensaio final treinou oito épocas, 456 passos, cobrindo todas as 226 amostras por época. Foram aproximadamente 630 segundos no CPU local com quatro threads. O conjunto contém 25 rótulos/aliases positivos de 14 medicamentos, mais abstensão; cada rótulo positivo tem nove exemplos. Os nomes/segmentações se repetem entre treino e teste, mas o layout de teste é reservado. Portanto esta avaliação mede mudança de layout sintético, não generalização para nomes nunca vistos.

As respostas brutas sobre documentos inteiros e sua validação posterior estão em `base.json`, `legacy_v2.json` e `candidate.json`; a geração bruta do candidato não equivale ao resultado de produção. A comparação decisiva está em `base_runtime_independent.json` e `candidate_runtime_independent.json`, com chamadas reais ao runtime e ao portão por trecho. O smoke HTTP também usa o adapter carregado do disco pelo caminho padrão.

| Fluxo | Positivos corretos | Falsos aceites (4 negativos) | Mediana / p95 (29 casos) |
|---|---:|---:|---:|
| Base sem adapter + runtime | 0/25 | 0/4 | 875 / 1033 ms |
| Adapter real + runtime | 23/25 (92%) | 0/4 | 356 / 497 ms |

Testes: **56 aprovados**, log em `backend_recovery_validation/tests.log`.
Receita de tres itens: 769 ms na avaliacao independente. Startup real: 4516 ms.
Probe de cancelamento: orcamento 1 ms, retorno em 140 ms, resposta descartada e lock liberado.
SHA-256 do adapter ativo: `300c79d677735dca594aa3345778ea9e9fd189da5dfc3c70a5a92968bbc71cca`.
Revisao base: `12fd25f77366fa6b3b4b768ec3050bf629380bac`.

A avaliação de segurança inclui oito casos adicionais: identidades distintas na embalagem (exatas e quebradas), nomes fictícios, instrução hostil com nome real e dose de um item seguinte que não pode ser atribuída ao anterior. Documento longo com múltiplas linhas e linha única longa também passaram. Um smoke anterior falhou na linha única longa; o fragmento OCR foi focado e as medições foram repetidas. O registro anterior permanece em `catalog_real_active/http_smoke_before_focus.json`.

## Limitações e pendências

- Falhas remanescentes nos positivos: ácido acetilsalicílico e ibuprofeno no layout quebrado do conjunto de teste. Não foram transformadas em aceitações com limiar mais baixo.
- Avaliação pequena e sintética; zero falsos aceites em quatro negativos e oito casos de segurança não prova ausência de risco. Ampliar negativos, fotos/OCR reais, catálogo, manuscritos e posologias complexas. A cobertura do treino não corresponde a todas as 10.777 entradas DCB.
- Prazo cooperativo, não deadline rígido de processo: o modelo interrompe entre tokens e descarta resultado tardio. Uma operação CPU/prefill já iniciada pode ultrapassar o orçamento. O probe real e sua ultrapassagem constam nas métricas. Um worker isolado é pendência para cancelamento rígido.
- O parser ainda depende de datas explicitamente rotuladas, lê contexto de até três linhas e consolida nomes repetidos na receita. Itens sem separador na mesma linha e nomes quebrados entre linhas podem ser recusados.
- GGUF real não foi exportado/avaliado. O default aprovado usa FP32. V2 e seus pesos/GGUF foram preservados; não recebem o prompt novo no runtime.
- Validar integração pelo Maestro no inicializador e no emulador/aparelho: rede, deadlines Android, câmera, fala/vibração e políticas de fallback continuam fora deste escopo. Não foi feito teste em dispositivo nem PostgreSQL real.

Comandos e critérios completos: `RECUPERACAO_CATALOGO_REAL.md`. Para repetir o smoke: `.tools/finetuning-venv/Scripts/python.exe GuiaMed/scripts/smoke_catalog_api.py`. Para testes: `.tools/finetuning-venv/Scripts/python.exe GuiaMed/scripts/verify_ia_backend.py`.
