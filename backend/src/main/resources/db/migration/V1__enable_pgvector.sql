-- Phase 1 initializes infrastructure; business tables start in Phase 2.
CREATE EXTENSION IF NOT EXISTS vector;

-- Abort on an incompatible database instead of silently mixing environments.
DO $$
BEGIN
    IF current_setting('server_version_num')::integer < 160000
       OR current_setting('server_version_num')::integer >= 170000 THEN
        RAISE EXCEPTION 'PostgreSQL 16 is required';
    END IF;
    IF (SELECT extversion FROM pg_extension WHERE extname = 'vector') <> '0.8.6' THEN
        RAISE EXCEPTION 'pgvector 0.8.6 is required';
    END IF;
END
$$;
