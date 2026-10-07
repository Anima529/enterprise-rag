package com.example.rag.document.dto;

import java.time.Instant;

public record DownloadResponse(String url, Instant expiresAt) {}
