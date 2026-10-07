-- V12 was already applied during upgrade verification; preserve its checksum.
-- PostgreSQL resolves record fields before boolean short-circuiting. Use a separate IF for each table shape.
CREATE OR REPLACE FUNCTION enforce_document_state_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE built boolean;
BEGIN
    IF TG_TABLE_NAME = 'document' THEN
        IF NEW.current_version <> OLD.current_version THEN
            EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.document_version WHERE document_id=$1 AND version_no=$2 AND status=''COMPLETED'')',TG_TABLE_SCHEMA)
                INTO built USING NEW.id,NEW.current_version;
            IF NEW.current_version > OLD.current_version AND NEW.status='COMPLETED' AND built THEN RETURN NEW; END IF;
            RAISE EXCEPTION 'version must advance to completed index' USING ERRCODE='23514';
        END IF;
    END IF;
    IF OLD.status=NEW.status OR
       (NEW.status='FAILED' AND OLD.status<>'COMPLETED') OR
       (OLD.status='UPLOADED' AND NEW.status='PARSING') OR
       (OLD.status='PARSING' AND NEW.status='CHUNKING') OR
       (OLD.status='CHUNKING' AND NEW.status='EMBEDDING') OR
       (OLD.status='EMBEDDING' AND NEW.status='INDEXING') OR
       (OLD.status='INDEXING' AND NEW.status='COMPLETED') OR
       (OLD.status='FAILED' AND NEW.status IN ('UPLOADED','PARSING')) THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'illegal document state transition: % -> %',OLD.status,NEW.status USING ERRCODE='23514';
END $$;
