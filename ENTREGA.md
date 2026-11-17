# Entrega de andamento — GuiaMed

**Grupo:** GuiaMed  
**Componentes:** João Roberto Santos Cardoso — 231070711; Gabriel de Souza
Nascimento — 231071404; Daniel Queiroga Santana Martins — 231070654; Pedro
Henrique Freitas Couto — 231071331; Pedro Felipe dos Anjos Santos — 232070649.  
**Disciplinas:** Projeto de Inteligência Artificial e Segurança e Otimização de BD.  
**Repositório:** https://github.com/Roberto-Cardoso-1/GuiaMed

## Parte comum

- Relatório de 1–2 páginas: `entrega_17_11/Relatorio_Andamento_17_11.pdf`.
- README com execução do app, backend, testes e evidências.
- Contribuição individual de João Roberto no relatório.

## Parte de IA

- RAG local e workflow n8n executado com auditoria no PostgreSQL:
  `evidencias/n8n/execucao_workflow_banco.txt`.
- Prompts versionados: `prompts/v1.md`, `prompts/v2.md` e changelog.
- Cinco casos com entrada, esperado e obtido: `evidencias/ia/casos_teste.md`.
- Ajuste supervisionado local: 300 exemplos, baseline 80% e modelo ajustado
  100% no teste sintético: `evidencias/ia/experimento_ajuste_local.md`.
- Latência e custo medidos: `evidencias/ia/metricas.json`.

## Parte de Segurança

- Papéis, menor privilégio, funções seguras, RLS e mascaramento:
  `db/postgres/01_security.sql`.
- Volume de 50 mil chunks e 1 milhão de eventos:
  `db/postgres/02_seed_volume.sql` e `evidencias/seguranca/volume.txt`.
- Duas consultas lentas com planos antes/depois:
  `db/postgres/04_planos_execucao.sql` e evidência correspondente.

## Parte de integração

- O perfil da IA não lê tabelas e usa somente funções autorizadas.
- Auditoria registra solicitante anonimizado, operação, fontes e horário.
- Script e saída: `db/postgres/03_evidencias_integracao.sql` e
  `evidencias/seguranca/permissoes_integracao.txt`.

Todos os dados usados nas provas são fictícios ou anonimizados. Não há chaves,
senhas, `.env`, keystore ou banco local do n8n no repositório.
