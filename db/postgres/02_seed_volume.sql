\set ON_ERROR_STOP on
\timing on

TRUNCATE TABLE guiamed_private.bula_chunk RESTART IDENTITY;
TRUNCATE TABLE guiamed_private.audit_log;
TRUNCATE TABLE guiamed_private.allergy RESTART IDENTITY;

-- 50.000 chunks inteiramente sintéticos para teste de volume.
INSERT INTO guiamed_private.bula_chunk(medicine, section, content)
SELECT 'Medicamento Fictício ' || ((g - 1) % 1000 + 1),
       (ARRAY['indicacao','posologia','contraindicacoes','efeitos_adversos','interacoes','advertencias'])[((g - 1) % 6) + 1],
       'Conteúdo didático sintético número ' || g ||
       '. Não representa orientação médica nem dado de paciente.'
FROM generate_series(1, 50000) AS series(g);

-- 1.000.000 de eventos anonimizados e fictícios.
INSERT INTO guiamed_private.audit_log(
    requester_hash, operation, query_fingerprint, sources,
    requested_at, latency_ms, cost_usd, danger
)
SELECT encode(digest('device-ficticio-' || (g % 1000), 'sha256'), 'hex'),
       CASE WHEN g % 3 = 0 THEN 'rag_local' ELSE 'ocr_local' END,
       encode(digest('consulta-ficticia-' || g, 'sha256'), 'hex'),
       jsonb_build_array('medicamento-ficticio#indicacao'),
       timestamptz '2026-10-01 00:00:00-03' + (g % 4000000) * interval '1 second',
       (g % 80)::numeric + 0.5,
       0,
       (g % 20 = 0)
FROM generate_series(1, 1000000) AS series(g);

ANALYZE guiamed_private.bula_chunk;
ANALYZE guiamed_private.audit_log;

SELECT count(*) AS bula_chunks FROM guiamed_private.bula_chunk;
SELECT count(*) AS audit_rows FROM guiamed_private.audit_log;
