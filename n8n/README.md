# n8n local — GuiaMed

Workflow versionado: `workflows/guiamed_local_rag.json`.

O fluxo é executado em uma instância n8n auto-hospedada e contém três etapas:

1. gatilho manual de evidência;
2. chamada HTTP para `POST /rag/consultar` na API FastAPI local;
3. validação de que a resposta tem fonte, auditoria, latência e custo.

Não há credenciais no JSON e nenhuma API SaaS é chamada. O resultado da execução
fica em `evidencias/n8n/execucao_workflow_banco.txt`.

Para repetir, inicie o backend local e importe o workflow:

```powershell
n8n import:workflow --input=n8n/workflows/guiamed_local_rag.json
n8n execute --id=guiamedLocalRagV1
```

O n8n exige Node 20.19–24.x. Consulte a documentação oficial antes de instalar
ou atualizar a ferramenta.

# Demonstração de acesso por perfil

Use um banco descartável com os papéis e funções de `db/postgres/00_roles_admin.sql`
e `01_security.sql` já instalados. Execute `05_seed_demo_perfis.sql` com a conta
da API e configure dois tokens aleatórios distintos de pelo menos 32 caracteres
no ambiente do backend local (`GUIAMED_DEMO_TOKEN_A` e `GUIAMED_DEMO_TOKEN_B`).
Inicie a API escutando apenas em `127.0.0.1`.

Importe `workflows/guiamed_demo_perfis.json`. Crie duas credenciais Header Auth
no n8n com nome de cabeçalho `Authorization` e valor `Bearer <token>`. Selecione
a credencial A no nó HTTP e execute. Esperado: HTTP 200 e
`alergia_registrada: true`. Em seguida selecione B e execute o mesmo nó.
Esperado: HTTP 403 e `Acesso negado`, com o identificador da auditoria.

Com o perfil de suporte, busque os dois identificadores na visão
`guiamed_api.support_audit_masked`. Mostre as operações
`demo_alergia_permitida` e `demo_alergia_negada`, o solicitante mascarado,
o marcador da consulta e o horário. Capture apenas os resultados, sem abrir
as credenciais na imagem.
