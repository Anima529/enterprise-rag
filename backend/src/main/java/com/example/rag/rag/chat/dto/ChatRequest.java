package com.example.rag.rag.chat.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record ChatRequest(
    @NotNull @Positive Long conversationId,
    @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> knowledgeBaseIds,
    @NotBlank @Size(max = 4000) String message
) {}
