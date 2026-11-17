\set ON_ERROR_STOP on

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS guiamed_private;
CREATE SCHEMA IF NOT EXISTS guiamed_api;

REVOKE ALL ON SCHEMA guiamed_private FROM PUBLIC;
REVOKE ALL ON SCHEMA guiamed_api FROM PUBLIC;

CREATE TABLE IF NOT EXISTS guiamed_private.allergy (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_hash char(64) NOT NULL CHECK (device_hash ~ '^[0-9a-f]{64}$'),
    substance varchar(160) NOT NULL CHECK (length(trim(substance)) BETWEEN 2 AND 160),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (device_hash, substance)
);

CREATE TABLE IF NOT EXISTS guiamed_private.audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_hash char(64) NOT NULL CHECK (requester_hash ~ '^[0-9a-f]{64}$'),
    operation varchar(40) NOT NULL,
    query_fingerprint char(64) NOT NULL CHECK (query_fingerprint ~ '^[0-9a-f]{64}$'),
    sources jsonb NOT NULL DEFAULT '[]'::jsonb,
    requested_at timestamptz NOT NULL DEFAULT now(),
    latency_ms numeric(12,3) NOT NULL DEFAULT 0 CHECK (latency_ms >= 0),
    cost_usd numeric(12,6) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    danger boolean NOT NULL DEFAULT false
);

CREATE TABLE IF NOT EXISTS guiamed_private.bula_chunk (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    medicine varchar(160) NOT NULL,
    section varchar(80) NOT NULL,
    content text NOT NULL,
    search_document tsvector GENERATED ALWAYS AS (
        to_tsvector('portuguese', medicine || ' ' || section || ' ' || content)
    ) STORED
);

ALTER TABLE guiamed_private.allergy ENABLE ROW LEVEL SECURITY;
ALTER TABLE guiamed_private.audit_log ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS allergy_owner_policy ON guiamed_private.allergy;
CREATE POLICY allergy_owner_policy ON guiamed_private.allergy
    FOR SELECT TO rl_paciente
    USING (device_hash = current_setting('app.device_hash', true)::char(64));

DROP POLICY IF EXISTS audit_owner_policy ON guiamed_private.audit_log;
CREATE POLICY audit_owner_policy ON guiamed_private.audit_log
    FOR SELECT TO rl_paciente
    USING (requester_hash = current_setting('app.device_hash', true)::char(64));

CREATE OR REPLACE FUNCTION guiamed_api.register_allergy(
    p_requester_hash text,
    p_substance text
) RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, guiamed_private
AS $function$
DECLARE
    cleaned text := lower(trim(p_substance));
BEGIN
    IF p_requester_hash !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'identificador anonimizado inválido' USING ERRCODE = '22023';
    END IF;
    IF length(cleaned) NOT BETWEEN 2 AND 160 THEN
        RAISE EXCEPTION 'alergia inválida' USING ERRCODE = '22023';
    END IF;
    INSERT INTO guiamed_private.allergy(device_hash, substance)
    VALUES (p_requester_hash, cleaned)
    ON CONFLICT (device_hash, substance) DO NOTHING;
    RETURN cleaned;
END
$function$;

CREATE OR REPLACE FUNCTION guiamed_api.check_allergy(
    p_requester_hash text,
    p_medicine text
) RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, guiamed_private
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM guiamed_private.allergy
        WHERE device_hash = p_requester_hash
          AND (
              lower(p_medicine) LIKE '%' || lower(substance) || '%'
              OR lower(substance) LIKE '%' || lower(p_medicine) || '%'
          )
    )
$function$;

CREATE OR REPLACE FUNCTION guiamed_api.record_consultation(
    p_requester_hash text,
    p_operation text,
    p_query_fingerprint text,
    p_sources jsonb,
    p_latency_ms numeric,
    p_cost_usd numeric
) RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, guiamed_private
AS $function$
DECLARE
    new_id uuid;
BEGIN
    IF p_requester_hash !~ '^[0-9a-f]{64}$'
       OR p_query_fingerprint !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'hash inválido' USING ERRCODE = '22023';
    END IF;
    INSERT INTO guiamed_private.audit_log(
        requester_hash, operation, query_fingerprint, sources, latency_ms, cost_usd
    ) VALUES (
        p_requester_hash,
        left(p_operation, 40),
        p_query_fingerprint,
        COALESCE(p_sources, '[]'::jsonb),
        GREATEST(COALESCE(p_latency_ms, 0), 0),
        GREATEST(COALESCE(p_cost_usd, 0), 0)
    ) RETURNING id INTO new_id;
    RETURN new_id;
END
$function$;

CREATE OR REPLACE FUNCTION guiamed_api.search_bulas(p_query text, p_limit integer DEFAULT 5)
RETURNS TABLE(chunk_id bigint, medicine varchar, section varchar, content text, rank real)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, guiamed_private
AS $function$
    SELECT id, medicine, section, content,
           ts_rank(search_document, websearch_to_tsquery('portuguese', p_query)) AS rank
    FROM guiamed_private.bula_chunk
    WHERE search_document @@ websearch_to_tsquery('portuguese', p_query)
    ORDER BY rank DESC, id
    LIMIT LEAST(GREATEST(p_limit, 1), 10)
$function$;

CREATE OR REPLACE VIEW guiamed_api.support_audit_masked AS
SELECT id,
       repeat('*', 56) || right(requester_hash, 8) AS requester_masked,
       operation,
       sources,
       requested_at,
       latency_ms,
       cost_usd,
       danger
FROM guiamed_private.audit_log;

REVOKE ALL ON ALL TABLES IN SCHEMA guiamed_private FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA guiamed_api FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA guiamed_api FROM PUBLIC;

GRANT USAGE ON SCHEMA guiamed_api TO rl_app_ia, rl_suporte, rl_farmaceutico;
GRANT EXECUTE ON FUNCTION guiamed_api.register_allergy(text, text) TO rl_app_ia;
GRANT EXECUTE ON FUNCTION guiamed_api.check_allergy(text, text) TO rl_app_ia;
GRANT EXECUTE ON FUNCTION guiamed_api.record_consultation(text, text, text, jsonb, numeric, numeric) TO rl_app_ia;
GRANT EXECUTE ON FUNCTION guiamed_api.search_bulas(text, integer) TO rl_app_ia;

GRANT USAGE ON SCHEMA guiamed_private TO rl_paciente, rl_farmaceutico;
GRANT SELECT ON guiamed_private.allergy, guiamed_private.audit_log TO rl_paciente;
GRANT SELECT, INSERT, UPDATE ON guiamed_private.bula_chunk TO rl_farmaceutico;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA guiamed_private TO rl_farmaceutico;
GRANT SELECT ON guiamed_api.support_audit_masked TO rl_suporte, rl_farmaceutico;

COMMIT;
