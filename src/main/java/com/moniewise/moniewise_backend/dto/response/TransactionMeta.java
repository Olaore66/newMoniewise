package com.moniewise.moniewise_backend.dto.response;

public record TransactionMeta(
        String sourceEnvelopeName,
        String targetEnvelopeName,
        String budgetName,
        Boolean isOutgoing
) {}