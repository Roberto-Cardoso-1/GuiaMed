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
