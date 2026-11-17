\set ON_ERROR_STOP on

-- Executar uma única vez como administrador do PostgreSQL. Os papéis não têm
-- LOGIN, senha, CREATEDB, CREATEROLE ou SUPERUSER.
DO $roles$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rl_app_ia') THEN
        CREATE ROLE rl_app_ia NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rl_paciente') THEN
        CREATE ROLE rl_paciente NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rl_suporte') THEN
        CREATE ROLE rl_suporte NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rl_farmaceutico') THEN
        CREATE ROLE rl_farmaceutico NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$roles$;

-- app_api recebe associação apenas para executar SET ROLE nas provas locais.
-- Os privilégios efetivos continuam definidos por cada papel.
GRANT rl_app_ia, rl_paciente, rl_suporte, rl_farmaceutico TO app_api;
