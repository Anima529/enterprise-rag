package com.example.rag.knowledgebase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record ReplaceAclRequest(@NotNull @Size(max = 100) List<@NotNull @Valid AclEntry> entries) {}
