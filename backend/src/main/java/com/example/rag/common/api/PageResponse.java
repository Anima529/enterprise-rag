package com.example.rag.common.api;

import java.util.List;

public record PageResponse<T>(List<T> records, long total, int page, int pageSize) {}
