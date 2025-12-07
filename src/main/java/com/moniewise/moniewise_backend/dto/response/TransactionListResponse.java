// File: src/main/java/com/moniewise/moniewise_backend/dto/response/TransactionListResponse.java
package com.moniewise.moniewise_backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionListResponse(
        Long id,
        String title,
        String description,
        BigDecimal amount,           // negative if outgoing
        BigDecimal fee,
        String type,
        LocalDateTime createdAt,
        TransactionMeta meta
) {
    // Optional: you can add a compact constructor if needed later
}