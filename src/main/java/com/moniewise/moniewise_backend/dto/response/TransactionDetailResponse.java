// src/main/java/com/moniewise/moniewise_backend/dto/transaction/TransactionDetailResponse.java
package com.moniewise.moniewise_backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionDetailResponse(
        Long id,
        String title,
        String fullDescription,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal netAmount,            // amount - fee (for clarity)
        String transactionType,
        LocalDateTime createdAt,

        // Context
        Long budgetId,
        String budgetName,
        String sourceEnvelopeName,
        Long sourceEnvelopeId,
        String targetEnvelopeName,
        Long targetEnvelopeId,

        // For future: external transfers
        String externalAccountId
) {}