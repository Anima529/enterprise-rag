package com.example.rag.system.dto;

import com.example.rag.system.domain.DatabaseInfo;
import java.time.Instant;
import java.util.Map;

public record SystemStatusResponse(
    String service,
    Instant checkedAt,
    DatabaseInfo database,
    Map<String, String> dependencies
) {}
