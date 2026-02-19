// src/main/java/com/moniewise/moniewise_backend/dto/transaction/TransactionDetailResponse.java
package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionDetailResponse(
        Long id,
        String reference,                // ✅ ADDED: For tracking/receipts
        String status,                   // ✅ ADDED: COMPLETED, FAILED, PENDING
        String direction,                // ✅ ADDED: "CREDIT" or "DEBIT"
        String title,
        String fullDescription,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal netAmount,            // amount - fee (for clarity)
        // 👇 ADDED THESE TWO 👇
        String sender,
        String recipient,
        TransactionType transactionType,
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


