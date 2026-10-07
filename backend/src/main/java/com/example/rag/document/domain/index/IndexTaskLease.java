package com.example.rag.document.domain.index;

import java.time.Instant;

public record IndexTaskLease(
    long id,
    long documentId,
    int documentVersion,
    String status,
    IndexStage stage,
    int attemptCount,
    int retryCount,
    int autoRetryCount,
    String leaseOwner,
    Instant leaseExpiresAt,
    boolean live,
    boolean leaseValid
) {}
