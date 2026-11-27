# n8n local — RAG generativo do GuiaMed

Workflow: `workflows/guiamed_local_rag.json`. O fluxo faz duas chamadas HTTP reais
a `POST /rag/consultar`, sequenciais, com **a mesma pergunta fictícia** e versões
`v1` e `v2`. A API executa busca e geração local em thread; o n8n registra o
resultado devolvido pela API, sem simular resposta nem importar modelo OCR.

1. `Iniciar evidência` inicia a comparação manual.
2. `Gerar RAG v1` envia a pergunta, identificador fictício, timestamp atual e `prompt_version=v1`.
3. `Validar v1` preserva a resposta completa e classifica geração e persistência separadamente.
4. `Gerar RAG v2` repete a mesma pergunta com `prompt_version=v2`.
5. `Validar v2` preserva os metadados e compara hash da pergunta, modelo e revisão.

Não há credenciais, leitura de variáveis de ambiente ou API SaaS no workflow.
Use apenas os dados fictícios fornecidos: as saídas dos nós ficam no histórico
de execuções n8n, configurado para salvar execuções manuais, sucessos e erros.
O JSON não produz automaticamente um arquivo de evidência; exporte a execução
se precisar anexá-la à apresentação. As evidências antigas não comprovam esta
versão generativa.

## Executar a demonstração

O Maestro deve preparar/iniciar o backend com as dependências opcionais e os
pesos locais descritos em `../backend/RAG_INTERFACE.md`. A API permanece
disponível para OCR quando o runtime RAG está ausente. O workflow tem timeout
HTTP de 120 s, sem retries; o cold load pode ultrapassar o prazo cooperativo de
geração. Um timeout HTTP não significa que a operação CPU já parou.

Importe na instância local e execute:

```powershell
n8n import:workflow --input=n8n/workflows/guiamed_local_rag.json
n8n execute --id=guiamedLocalRagV1
```

O endereço `127.0.0.1:8000` pressupõe n8n e API na mesma máquina/namespace de rede.
Em uma instalação isolada, ajuste explicitamente a URL dos dois nós HTTP.
Não reinicie serviços como parte dos testes de contrato.

## Interpretar geração e persistência

| Campo do workflow | Significado |
|---|---|
| `LLM_REAL_VALIDADO_PELO_FILTRO` | API informa `llm_called=true`, `generator_backend=transformers`, `mode=llm_grounded` e saída aceita pelo filtro. |
| `FALLBACK_EXTRATIVO` | Texto extraído por template, com `fallback_reason` explícito; não comprova resposta gerada por LLM. |
| `RECUSA` | Sem evidência suficiente ou entrada hostil; não é uma geração bem-sucedida. |
| `GERACAO_NAO_COMPROVADA` | Inclui backend fake usado nos testes. |
| `POSTGRESQL_PERSISTIDO` | Recibo declara `backend=postgresql`, `persistida=true` e ID correspondente. |
| `MEMORIA_NAO_PERSISTIDA` | A API devolveu recibo em memória, perdido ao encerrar o processo. |
| `GERACAO_E_PERSISTENCIA_COMPROVADAS` | Combina geração real aceita, tokens, fontes, versão solicitada e recibo PostgreSQL. |

Um `auditoria_id` isolado **não comprova banco**. `geracao_llm_real` não deve ser
confundido com `generation_attempted`: a tentativa pode falhar antes de chamar
o modelo. O recibo confirma a escrita observada pela API, não uma validação
independente do banco; a visão autorizada de suporte permite conferir seu ID.

Falha de importação/execução da integração devolve HTTP 503 com mensagem
audível, metadados seguros e recibo da tentativa; o nó HTTP registra falha.
Indisponibilidade tratada pelo runtime pode devolver HTTP 200 com
`mode=fallback_template`, motivo e aviso no texto. Isso nunca recebe o rótulo
de geração comprovada. O workflow não converte HTTP 503 em sucesso.

**Qualidade textual continua pendente de avaliação humana/benchmark.** Fontes
válidas, tokens e filtro não medem qualidade da redação nem melhora de v2.
`citation_origin` distingue citação produzida pelo modelo, adicionada pelo
sistema e template. O workflow fixa `qualidade_textual=NAO_AVALIADA_POR_ESTE_WORKFLOW`.
Para comparação controlada com os mesmos chunks/ordem/scores, use a interface
`hits` descrita em `RAG_INTERFACE.md`; a API pública não aceita chunks ou prompts
arbitrários. Nesta demonstração a pergunta é idêntica; os campos `fontes`
representam fontes selecionadas na resposta, não todos os hits recuperados.

## Contrato, tempos, custos e privacidade

O request mantém `device_id`, `pergunta`, `timestamp` e aceita opcionalmente
`prompt_version`, somente `v1` ou `v2` (padrão). Campos extras como `prompt`,
`system_prompt`, `sql` ou nome de modelo são rejeitados com HTTP 422.

O response preserva `resposta`, `fontes`, `medicamento`, `confianca`,
`prompt_version`, `latencia_ms`, `custo_usd`, `auditoria_id` e
`entrada_hostil_detectada`. Os novos campos são mantidos integralmente pelos nós:

- `busca_ms`, `geracao_ms`, `load_ms`, `total_ms`; `load_ms` é parte de geração,
  portanto não deve ser somado duas vezes.
- `input_tokens`, `output_tokens`, `model`, `revision`, `prompt_sha256`.
  Tokens são contagens do runtime; `null` significa indisponíveis, não zero.
- `mode`, `fallback`, `fallback_reason`, `generation_attempted`, `llm_called`,
  `generator_backend`, `geracao_llm_real`, `citation_origin`, `consulta_sha256`.
- `auditoria: {id, backend, persistida}` e `custos`.

`latencia_ms=total_ms` mede tempo de processamento observado pela API desde
entrada na rota até receber a saída, incluindo fila da thread, criação do
índice/carregamento, busca e geração. Esse total e o custo são enviados à
auditoria. A métrica termina antes da escrita da própria auditoria e não inclui
serialização/transporte HTTP. O tempo do nó n8n inclui esses custos adicionais.

`custo_usd` é cobrança de APIs externas reportada pelo runtime (zero no modelo
local). **Energia e hardware não foram medidos**: `custos.energia_medida=false`,
`energia_usd=null` e `hardware_usd=null`. Zero não significa operação gratuita.

A auditoria usa o pool existente e apenas `guiamed_api.record_consultation`,
com parâmetros SQL. O argumento JSONB existente recebe fontes, resposta final,
SHA-256 da resposta e metadados de execução, sem migração de tabela. Solicitante
e pergunta são SHA-256; pergunta, prompt e resposta bruta não são gravados nem
expostos. A resposta final persistida vem somente das bulas fictícias ou de uma
mensagem de recusa. Hashes não eliminam todos os riscos de reidentificação; use
dados fictícios na demonstração.

O Android foi conferido em `ApiModels.kt`, `GuiaMedApi.kt` e
`GuiaMedRepository.kt`: não consome `/rag/consultar`. Nenhuma alteração Android
é necessária. Os contratos de `/verificar`, `/receita` e `/alergias` permanecem.

Os testes `backend/test_rag_api_integration.py` usam ASGI, runtime injetado e pool
simulado para validar contrato, event loop, falhas e auditoria sem reiniciar
serviços. A execução real do LLM e a persistência real no PostgreSQL precisam
ser demonstradas no ambiente preparado pelo Maestro.

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
