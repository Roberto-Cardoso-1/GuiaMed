# Evidências da entrega de 17/11

| Requisito | Evidência reproduzível |
|---|---|
| RAG + n8n ponta a ponta | `n8n/execucao_workflow_banco.txt` e workflow versionado |
| Prompts versionados | `../prompts/v1.md`, `v2.md` e `CHANGELOG.md` |
| Cinco casos de IA | `ia/casos_teste.md` |
| Ajuste supervisionado | `ia/experimento_ajuste_local.md` e `ia/metricas.json` |
| Custo e latência | `ia/metricas.json` |
| Papéis, RLS e mascaramento | `seguranca/permissoes_integracao.txt` |
| Volume | `seguranca/volume.txt` |
| Planos de execução | `seguranca/planos_execucao.txt` |
| Auditoria da IA | `seguranca/auditoria_n8n.txt` e `permissoes_integracao.txt` |

## Prints para anexar à entrega

- `prints/01_n8n_execucao.png`: execução ponta a ponta com três nós em
  `success`, fonte, auditoria e `status_evidencia: PASSOU`.
- `prints/02_seguranca_resumo.png`: oito verificações automatizadas aprovadas.
- `prints/03_integracao_permissoes.png`: acesso direto negado, função autorizada,
  mascaramento, auditoria e RLS separando os dispositivos A e B.

Todos os dados usados nas evidências são fictícios ou anonimizados. Arquivos de
configuração local, credenciais, banco do n8n e ambiente Python não são
versionados.
