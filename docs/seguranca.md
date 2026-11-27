# Segurança, otimização e integração — GuiaMed

O banco acadêmico é PostgreSQL local e usa apenas dados fictícios. Identidades
de dispositivo e consultas são persistidas como SHA-256, nunca em texto livre.

## Matriz final de papéis e permissões

| Papel | Permitido | Negado e teste que comprova |
|---|---|---|
| `rl_app_ia` | Executar funções autorizadas de alergia, RAG e auditoria | `SELECT` nas tabelas privadas retorna `permission denied` |
| `rl_paciente` | Ler somente alergias/auditoria do hash da sessão | RLS: perfil A vê 1 linha e B vê 0 |
| `rl_suporte` | Consultar visão de auditoria mascarada | Não recebe acesso às tabelas nem ao hash completo |
| `rl_farmaceutico` | Manter chunks públicos e consultar métricas permitidas | Não acessa alergias nem identificadores completos |

O PostgreSQL não possui `DENY` como o SQL Server. O DENY exigido é implementado
por `REVOKE`, ausência de `GRANT`, schemas privados e funções `SECURITY DEFINER`
com `search_path` fixo. A negação é justificada porque a IA precisa executar
operações específicas, mas não precisa consultar dados de saúde diretamente.

Evidência: `evidencias/seguranca/permissoes_integracao.txt`.

## Duas técnicas de proteção de dado sensível

1. **RLS:** políticas em `allergy` e `audit_log` comparam o hash da linha ao
   contexto `app.device_hash`. Um paciente não lê registros de outro.
2. **Mascaramento:** `guiamed_api.support_audit_masked` substitui os 56 primeiros
   caracteres do hash por asteriscos e mostra apenas os oito finais ao suporte.

Consultas parametrizadas (`$1`, `$2`, etc.), schemas privados e ausência de
segredos no repositório complementam essas proteções.

## Duas consultas lentas otimizadas

| Consulta | Antes | Depois | Técnica | Ganho aproximado |
|---|---:|---:|---|---:|
| Busca textual nos 50 mil chunks | 37,647 ms, `Seq Scan` | 0,120 ms, `Bitmap Index Scan` | full-text search + índice GIN | 313,7× |
| Alertas críticos em 1 milhão de auditorias | 109,734 ms, `Parallel Seq Scan` | 0,075 ms, `Index Only Scan` | índice parcial por data com `danger=true` | 1.463,1× |

Os planos foram obtidos com `EXPLAIN (ANALYZE, BUFFERS)`. Fonte bruta:
`evidencias/seguranca/planos_execucao.txt`; script reproduzível:
`db/postgres/04_planos_execucao.sql`.

## Ataque e correção

O escalonamento do papel da IA foi testado por acesso direto ao schema privado.
A tentativa foi negada, enquanto a função autorizada continuou funcionando. A
correção é menor privilégio com funções estreitas e `search_path` fixo. Detalhes
e prompt injection estão em `docs/ataques.md`.

## Trilha de auditoria

A auditoria registra:

- quem consultou: hash SHA-256 do solicitante;
- o que consultou: impressão digital da pergunta e fontes utilizadas;
- o que a IA respondeu: texto da resposta fictícia e SHA-256 no JSON auditado;
- quando: `requested_at`;
- como: versão do prompt, modelo, revisão, modo, tokens e origem da citação;
- desempenho/custo: latência e cobrança externa.

A pergunta original não é persistida para reduzir exposição de texto livre. A
resposta vem apenas das bulas fictícias ou de mensagens de recusa. A visão de
suporte mantém o solicitante mascarado. O n8n conserva o ID retornado pela API,
permitindo relacionar a execução à linha PostgreSQL.

## Prova por perfil

Na mesma consulta fictícia, o perfil A recebeu HTTP 200 e confirmou seu
registro; o perfil B recebeu HTTP 403. As duas tentativas foram auditadas como
`demo_alergia_permitida` e `demo_alergia_negada`, com solicitantes mascarados.
Assim, a camada de IA não contorna o que RLS e permissões protegem.

Evidências: `evidencias/n8n/demo_perfis_execucao.txt`,
`evidencias/seguranca/auditoria_n8n.txt` e
`evidencias/n8n/rag_generativo_postgresql.md`.
