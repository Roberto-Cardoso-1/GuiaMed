# Evidência n8n

- n8n auto-hospedado: versão 2.6.4.
- Node portátil usado na execução: 24.22.3.
- Workflow: `n8n/workflows/guiamed_local_rag.json`.
- Resultado: `status: success`, três nós com `executionStatus: success` e
  `status_evidencia: PASSOU`.
- Fonte recuperada: `pressormin#indicacao`.
- Auditoria criada e custo monetário igual a zero.

A saída integrada ao PostgreSQL está em `execucao_workflow_banco.txt`. A
auditoria correspondente, consultada pelo perfil de suporte com hash mascarado,
está em `../seguranca/auditoria_n8n.txt`.

O início do log registra um aviso do executor Python opcional do n8n. O workflow
não possui nós Python; por isso o aviso não afetou a execução, que terminou com
os três nós em `success` e a validação em `PASSOU`.
