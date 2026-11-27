# Evidências da entrega de 17/11

| Requisito | Evidência reproduzível |
|---|---|
| RAG generativo local (`SmolLM2-135M-Instruct`) e comparação pareada `v1` vs `v2` | [Comparação pareada `v1`/`v2`](ia/rag_prompt_comparison/comparison.md) e [métricas brutas](ia/rag_prompt_comparison/results.json) |
| RAG generativo + n8n + PostgreSQL | [Validação ponta a ponta por ID de auditoria](n8n/rag_generativo_postgresql.md) e [workflow v1/v2](../n8n/workflows/guiamed_local_rag.json) |
| Prompts versionados | `../prompts/v1.md`, `v2.md` e `CHANGELOG.md` |
| Cinco casos de IA | `ia/casos_teste.md` |
| Ajuste supervisionado | `ia/experimento_ajuste_local.md` e `ia/metricas.json` |
| Fine-tuning de LLM pré-treinado | [Treino LoRA e comparação com o original](ia/finetuning/README.md) |
| Segunda rodada de fine-tuning | [Comparação dos adaptadores em layouts inéditos](ia/finetuning_v2/README.md) |
| Custo e latência | [Resultados da comparação RAG](ia/rag_prompt_comparison/results.json) e `ia/metricas.json` |
| Papéis, RLS e mascaramento | `seguranca/permissoes_integracao.txt` |
| Volume | `seguranca/volume.txt` |
| Planos de execução | `seguranca/planos_execucao.txt` |
| Auditoria da IA | `seguranca/auditoria_n8n.txt` e `permissoes_integracao.txt` |
| Consulta protegida com dois perfis no n8n | [Respostas HTTP e auditoria no PostgreSQL](n8n/demo_perfis_execucao.txt) |

## Prints para anexar à entrega

- `prints/01_n8n_execucao.png`: execução ponta a ponta com três nós em
  `success`, fonte, auditoria e `status_evidencia: PASSOU`.
- `prints/02_seguranca_resumo.png`: oito verificações automatizadas aprovadas.
- `prints/03_integracao_permissoes.png`: acesso direto negado, função autorizada,
  mascaramento, auditoria e RLS separando os dispositivos A e B.
- [`prints/04_integracao_n8n_perfis.png`](prints/04_integracao_n8n_perfis.png):
  resposta permitida para A, acesso negado para B e os dois registros de auditoria.

A [receita fictícia de demonstração](receita_ficticia_demonstracao.png) é
uma amostra para testar a leitura pela câmera.

O [resumo visual do fine-tuning](prints/05_finetuning_llm.png) foi gerado
a partir das métricas e do histórico do treino local. Os arquivos completos
estão em [`ia/finetuning/`](ia/finetuning/).

Todos os dados usados nas evidências são fictícios ou anonimizados. Arquivos de
configuração local, credenciais, banco do n8n e ambiente Python não são
versionados.
