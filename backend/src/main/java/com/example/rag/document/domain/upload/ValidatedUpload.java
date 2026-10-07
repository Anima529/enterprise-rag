package com.example.rag.document.domain.upload;

import com.example.rag.document.domain.DocumentFormat;

public record ValidatedUpload(String name, DocumentFormat format, byte[] content, String sha256) {}
