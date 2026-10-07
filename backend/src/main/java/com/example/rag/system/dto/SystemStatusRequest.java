package com.example.rag.system.dto;

import jakarta.validation.constraints.Pattern;

public record SystemStatusRequest(
    @Pattern(regexp = "zh-CN|en", message = "locale must be zh-CN or en") String locale
) {}
