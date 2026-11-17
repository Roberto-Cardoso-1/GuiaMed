\set ON_ERROR_STOP on
\pset pager off

\echo 'Q1 ANTES - busca textual não indexável com ILIKE'
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, medicine, section
FROM guiamed_private.bula_chunk
WHERE content ILIKE '%sintético número 49999%';

\echo 'Q1 DEPOIS - busca textual com índice GIN e full-text search'
CREATE INDEX IF NOT EXISTS ix_bula_chunk_search
    ON guiamed_private.bula_chunk USING gin(search_document);
ANALYZE guiamed_private.bula_chunk;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, medicine, section
FROM guiamed_private.bula_chunk
WHERE search_document @@ websearch_to_tsquery('portuguese', 'sintético 49999');

\echo 'Q2 ANTES - filtro temporal e perigo sem índice composto'
DROP INDEX IF EXISTS guiamed_private.ix_audit_requested_danger;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT date_trunc('day', requested_at) AS day, count(*)
FROM guiamed_private.audit_log
WHERE requested_at >= timestamptz '2026-11-10 00:00:00-03'
  AND danger = true
GROUP BY 1
ORDER BY 1;

\echo 'Q2 DEPOIS - índice parcial por data para alertas críticos'
CREATE INDEX ix_audit_requested_danger
    ON guiamed_private.audit_log(requested_at)
    WHERE danger = true;
ANALYZE guiamed_private.audit_log;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT date_trunc('day', requested_at) AS day, count(*)
FROM guiamed_private.audit_log
WHERE requested_at >= timestamptz '2026-11-10 00:00:00-03'
  AND danger = true
GROUP BY 1
ORDER BY 1;
