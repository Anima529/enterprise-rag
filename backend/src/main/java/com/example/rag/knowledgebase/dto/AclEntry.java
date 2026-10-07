package com.example.rag.knowledgebase.dto;

import com.example.rag.knowledgebase.domain.Permission;
import com.example.rag.knowledgebase.domain.SubjectType;
import jakarta.validation.constraints.*;

public record AclEntry(
    @NotNull SubjectType subjectType,
    @NotBlank @Size(max = 32) String subjectId,
    @NotNull Permission permission
) {}
