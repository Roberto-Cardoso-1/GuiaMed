# Evidência da auditoria de resposta da IA

Data: 27/11/2026.

Comando executado:

```powershell
cd backend
.\.venv\Scripts\python.exe -m unittest -v test_rag_api_integration
```

Resultado: **13 testes aprovados**. Os testes verificam que:

- o dispositivo e a pergunta são convertidos em SHA-256;
- o texto da resposta fictícia e seu SHA-256 entram no JSON de auditoria;
- a escrita PostgreSQL usa apenas `guiamed_api.record_consultation` com
  parâmetros, sem SQL interpolado;
- o recibo distingue PostgreSQL persistido de fallback em memória;
- erros não registram pergunta, dispositivo ou segredo nos logs.

A suíte completa do backend também foi executada: **99 testes aprovados**. A
persistência PostgreSQL real e os IDs n8n estão nas evidências anteriores
`permissoes_integracao.txt`, `auditoria_n8n.txt` e
`../n8n/rag_generativo_postgresql.md`. O banco padrão atual do workspace não
tem o papel `rl_app_ia`; por isso a reprodução fim a fim exige provisionar um
banco isolado com `db/postgres/00_roles_admin.sql` e `01_security.sql`, como
documentado no guia n8n.
