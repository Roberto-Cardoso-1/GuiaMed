# Entrega final — GuiaMed — João Roberto

**Grupo:** GuiaMed  
**Componentes:** João Roberto Santos Cardoso — 231070711; Gabriel de Souza
Nascimento — 231071404; Daniel Queiroga Santana Martins — 231070654; Pedro
Henrique Freitas Couto — 231071331; Pedro Felipe dos Anjos Santos — 232070649.  
**Discente desta entrega:** João Roberto Santos Cardoso — 231070711.  
**Disciplinas:** Projeto de Inteligência Artificial e Segurança e Otimização de BD.  
**Repositório:** https://github.com/Roberto-Cardoso-1/GuiaMed  
**Relatório:** [`entrega_final/Relatorio_Final_GuiaMed_Joao_Roberto.pdf`](entrega_final/Relatorio_Final_GuiaMed_Joao_Roberto.pdf)

## Parte comum

- O [README](README.md) explica problema, arquitetura, instalação, execução,
  testes e estrutura do projeto.
- O [relatório de limitações](docs/limitacoes.md) registra o que não funciona,
  riscos residuais e próximos passos.
- Todos os dados de demonstração são fictícios ou anonimizados.

### Contribuição individual — João Roberto

Implementei e integrei o fluxo Android de acessibilidade, incluindo OCR,
tratamento conservador de validade e feedback por voz, vibração e alto contraste.
Também trabalhei na API FastAPI, na recuperação local com RAG/LoRA e nos testes
de contrato. Estruturei as provas de segurança PostgreSQL, integração n8n e a
documentação/evidências reproduzíveis da entrega final.

## Parte de IA

- **Casos de teste:** 25 positivos, quatro negativos, oito verificações de
  segurança e casos de receita/documento longo; resultados e análise em
  [`evidencias/ia/casos_teste.md`](evidencias/ia/casos_teste.md). O adapter ativo
  acertou 23/25 positivos (92%), sem falso aceite nos quatro negativos.
- **Quatro cenários:** modelo base, base com pipeline de recuperação, modelo
  ajustado e ajustado com recuperação estão comparados em
  [`evidencias/ia/comparacao_quatro_cenarios.md`](evidencias/ia/comparacao_quatro_cenarios.md),
  com acerto, custo e latência e ressalvas metodológicas.
- **Prompt injection:** o caso hostil RAG-05 foi recusado em 3/3 repetições por
  versão, antes de chamar o LLM. Evidência em
  [`rag_prompt_comparison/results.json`](evidencias/ia/rag_prompt_comparison/results.json)
  e análise em [`docs/ataques.md`](docs/ataques.md).
- **Custo e latência:** cobrança externa medida de US$ 0 por 1.000 usos no
  processamento local; estimativa energética e latências estão documentadas na
  comparação. Energia real e depreciação de hardware não foram medidas.
- **n8n:** [`guiamed_local_rag.json`](n8n/workflows/guiamed_local_rag.json) e
  [`guiamed_demo_perfis.json`](n8n/workflows/guiamed_demo_perfis.json) estão
  exportados. Não contêm tokens, senhas ou IDs de credenciais; a credencial da
  demonstração é configurada localmente após a importação.

## Parte de Segurança e Otimização

- **Papéis e permissões:** matriz em [`docs/seguranca.md`](docs/seguranca.md).
  O `rl_app_ia` recebe `permission denied` ao tentar ler tabelas privadas e
  executa somente funções autorizadas — DENY efetivo por `REVOKE`/ausência de
  `GRANT`, apropriado ao PostgreSQL.
- **Proteção de dados:** RLS por hash de dispositivo e mascaramento da visão de
  suporte; consultas parametrizadas e schemas privados complementam as duas
  técnicas exigidas.
- **Otimização:** busca textual caiu de 37,647 ms para 0,120 ms com GIN/FTS;
  relatório de alertas caiu de 109,734 ms para 0,075 ms com índice parcial.
  Planos brutos em [`planos_execucao.txt`](evidencias/seguranca/planos_execucao.txt).
- **Ataque:** o escalonamento do papel da IA foi negado e a função permitida
  continuou operando. O teste e a correção estão em
  [`permissoes_integracao.txt`](evidencias/seguranca/permissoes_integracao.txt).
- **Auditoria:** registra solicitante anonimizado, operação, impressão digital
  da consulta, fontes, resposta da IA com SHA-256, horário, latência e custo;
  os testes estão em
  [`auditoria_resposta_teste.md`](evidencias/seguranca/auditoria_resposta_teste.md).

## Parte de integração

- Os dois ataques exigidos foram exercitados: prompt injection e escalonamento
  de privilégio.
- A execução n8n/PostgreSQL associa resposta, fontes e métricas ao ID de
  auditoria; a pergunta e o dispositivo permanecem anonimizados por SHA-256.
- No teste por perfil, A recebeu HTTP 200 e B recebeu HTTP 403 para a mesma
  consulta. As duas tentativas aparecem na auditoria mascarada em
  [`demo_perfis_execucao.txt`](evidencias/n8n/demo_perfis_execucao.txt).

## Observações de integridade

Os números acima vêm dos artefatos versionados e não são apresentados como
validação clínica. O relatório distingue medição de estimativa e documenta que
os benchmarks usam dados sintéticos. `.env`, tokens, keystores, banco local do
n8n e credenciais não fazem parte do repositório.
