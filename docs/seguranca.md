# Segurança, otimização e integração — GuiaMed

O banco de integração é PostgreSQL local. Todos os dados de demonstração são
fictícios e identificadores de dispositivo são armazenados somente como SHA-256.

## Papéis e menor privilégio

| Papel | Permitido | Negado por ausência de privilégio |
|---|---|---|
| `rl_app_ia` | Executar funções autorizadas de alergia, RAG e auditoria | Operações diretas nas tabelas privadas |
| `rl_paciente` | Ler somente alergias e auditorias do hash definido na sessão | Dados de qualquer outro dispositivo e toda escrita direta |
| `rl_suporte` | Consultar a visão de auditoria mascarada | Tabelas privadas e texto original das consultas |
| `rl_farmaceutico` | Manter chunks públicos e consultar métricas mascaradas | Alergias e identificadores completos |

PostgreSQL não possui a instrução `DENY` do SQL Server. O mesmo efeito é
implementado com `REVOKE`, ausência de `GRANT`, schemas privados e funções
`SECURITY DEFINER` com `search_path` fixo. A tentativa direta retorna
`permission denied`.

## Proteções aplicadas

- **RLS:** políticas em `allergy` e `audit_log` comparam o hash da linha ao
  contexto `app.device_hash` da sessão.
- **Mascaramento:** `guiamed_api.support_audit_masked` mostra somente os oito
  últimos caracteres do hash ao suporte.
- **Consultas parametrizadas:** o backend usa parâmetros `$1`, `$2`, etc.; não
  há interpolação de entrada OCR em SQL.
- **Auditoria mínima:** registra quem pediu (hash), operação, impressão digital
  da pergunta, fontes, horário, latência e custo — nunca o texto livre completo.
- **Falha segura:** a identidade da IA só recebe `EXECUTE` nas funções
  permitidas e não recebe `USAGE` no schema privado.

Os papéis sem login são criados uma única vez pelo administrador em
[`db/postgres/00_roles_admin.sql`](../db/postgres/00_roles_admin.sql). Os objetos
e privilégios ficam em
[`db/postgres/01_security.sql`](../db/postgres/01_security.sql).

## Base com volume

[`db/postgres/02_seed_volume.sql`](../db/postgres/02_seed_volume.sql) gera, de
forma set-based, 50.000 chunks sintéticos e 1.000.000 de eventos de auditoria
fictícios. A evidência contém o resultado real de `COUNT(*)`.

## Consultas lentas e planos

[`db/postgres/04_planos_execucao.sql`](../db/postgres/04_planos_execucao.sql)
executa `EXPLAIN (ANALYZE, BUFFERS)` antes e depois para:

1. busca textual com `ILIKE '%...%'`, substituída por full-text search com
   índice GIN;
2. relatório temporal de alertas críticos, otimizado com índice parcial em
   `requested_at WHERE danger = true`.

As saídas brutas ficam em `evidencias/seguranca/planos_execucao.txt`.

## Evidência de integração

[`db/postgres/03_evidencias_integracao.sql`](../db/postgres/03_evidencias_integracao.sql)
prova cinco situações:

1. `rl_app_ia` recebe erro ao consultar `allergy` diretamente;
2. o mesmo papel usa a função autorizada;
3. a IA grava auditoria com fonte e horário;
4. o suporte vê somente o hash mascarado;
5. RLS permite ao dispositivo A ver seu dado e impede o dispositivo B.

A saída fica em `evidencias/seguranca/permissoes_integracao.txt`.
