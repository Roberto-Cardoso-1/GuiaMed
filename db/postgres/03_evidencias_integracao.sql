\set ON_ERROR_STOP off
\pset pager off

\echo 'EVIDENCIA 1 - perfil da IA não pode consultar tabela diretamente'
SET ROLE rl_app_ia;
SELECT * FROM guiamed_private.allergy LIMIT 1;

\echo 'EVIDENCIA 2 - perfil da IA pode usar somente a função autorizada'
SELECT guiamed_api.register_allergy(
    encode(digest('aparelho-ficticio-a', 'sha256'), 'hex'),
    'dipirona'
) AS alergia_registrada;
SELECT guiamed_api.check_allergy(
    encode(digest('aparelho-ficticio-a', 'sha256'), 'hex'),
    'dipirona'
) AS alerta_alergia;

\echo 'EVIDENCIA 3 - auditoria registra quem, o que, fontes e quando'
SELECT guiamed_api.record_consultation(
    encode(digest('aparelho-ficticio-a', 'sha256'), 'hex'),
    'rag_local',
    encode(digest('para que serve pressormin', 'sha256'), 'hex'),
    '["pressormin#indicacao"]'::jsonb,
    0.8,
    0
) AS auditoria_id;

RESET ROLE;

\echo 'EVIDENCIA 4 - suporte vê somente identificador mascarado'
SET ROLE rl_suporte;
SELECT requester_masked, operation, sources, requested_at, latency_ms, cost_usd
FROM guiamed_api.support_audit_masked
WHERE operation = 'rag_local'
ORDER BY requested_at DESC
LIMIT 2;
RESET ROLE;

\echo 'EVIDENCIA 5 - RLS: paciente A não vê dados do paciente B'
SET ROLE rl_paciente;
SELECT set_config('app.device_hash', encode(digest('aparelho-ficticio-a', 'sha256'), 'hex'), false);
SELECT count(*) AS alergias_visiveis_para_a FROM guiamed_private.allergy;
SELECT set_config('app.device_hash', encode(digest('aparelho-ficticio-b', 'sha256'), 'hex'), false);
SELECT count(*) AS alergias_visiveis_para_b FROM guiamed_private.allergy;
RESET ROLE;
