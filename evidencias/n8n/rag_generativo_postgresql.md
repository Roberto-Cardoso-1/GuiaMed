# Validação do RAG generativo pelo n8n e PostgreSQL

Em 27/11/2026, o workflow `guiamedLocalRagV1` consultou a API local com a mesma pergunta fictícia nas duas versões. O SmolLM2-135M-Instruct (revisão `12fd25f77366fa6b3b4b768ec3050bf629380bac`) gerou as duas respostas por decodificação extrativa. Ambas passaram pelo filtro e citaram `pressormin#indicacao`, com citação emitida pelo modelo.

| Prompt | Busca (ms) | Geração (ms) | Total da rota (ms) | Tokens entrada/saída | ID confirmado no PostgreSQL |
|---|---:|---:|---:|---:|---|
| v1 | 0,507 | 3.678,750 | 3.680,065 | 309/63 | `3eb3285f-cd20-4b9e-af1b-aaa133387497` |
| v2 | 0,408 | 3.584,581 | 3.585,678 | 412/63 | `5d81e2f2-c46d-49cd-80b9-916345dac4ea` |

O resultado foi `GERACAO_E_PERSISTENCIA_COMPROVADAS` nas duas versões. Uma consulta independente à visão `guiamed_api.support_audit_masked`, com o papel `rl_suporte`, confirmou os dois IDs, a operação `rag_local`, a fonte, a versão do prompt e a latência registrada. A cobrança de API externa foi de US$ 0. Energia e hardware não foram medidos. O total da rota é aferido antes da gravação da auditoria.

Para reproduzir, prepare um PostgreSQL de demonstração com `00_roles_admin.sql` e `01_security.sql`, configure a API para esse banco e execute o [workflow conforme o guia](../../n8n/README.md#executar-a-demonstração). Esta execução usou um banco isolado porque o banco padrão do workspace retornou `InvalidSchemaNameError` na gravação da auditoria. Nesse banco padrão, o workflow identificou corretamente o fallback em memória. O esquema do banco padrão segue pendente de provisionamento.

## Comparação pareada

No [ensaio com cinco perguntas e trechos congelados](../ia/rag_prompt_comparison/comparison.md), v1 e v2 deram a mesma resposta em indicação e posologia. Na pergunta sobre interações do Cardiovast, v1 selecionou contraindicações e v2 selecionou o trecho pertinente de interações. As duas recusas esperadas ocorreram antes da chamada ao modelo. O caso de Cardiovast mostra que uma citação válida ainda pode acompanhar uma resposta que foge da pergunta. A melhora observada é pontual e o ensaio usa bulas fictícias.
