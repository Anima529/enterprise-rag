-- Add DOC without changing checksums or rows from V1-V6.
ALTER TABLE document DROP CONSTRAINT document_source_format_check;
ALTER TABLE document ADD CONSTRAINT document_source_format_check
    CHECK (source_format IN ('PDF','DOC','DOCX','XLSX','TXT','MD'));

ALTER TABLE document_version DROP CONSTRAINT document_version_source_format_check;
ALTER TABLE document_version ADD CONSTRAINT document_version_source_format_check
    CHECK (source_format IN ('PDF','DOC','DOCX','XLSX','TXT','MD'));

ALTER TABLE document_parse_result DROP CONSTRAINT document_parse_result_source_format_check;
ALTER TABLE document_parse_result ADD CONSTRAINT document_parse_result_source_format_check
    CHECK (source_format IN ('PDF','DOC','DOCX','XLSX','TXT','MD'));
