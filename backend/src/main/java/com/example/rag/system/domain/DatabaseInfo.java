package com.example.rag.system.domain;

public record DatabaseInfo(String databaseVersion, String vectorVersion, long migrations) {}
