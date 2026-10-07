package com.example.rag.document.dto;

import java.util.List;

public record UploadPolicyResponse(List<String> extensions, int maxBytes, int maxTextCharacters) {}
