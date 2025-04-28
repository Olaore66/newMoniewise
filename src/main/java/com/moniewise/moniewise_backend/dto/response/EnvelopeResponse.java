package com.moniewise.moniewise_backend.dto.response;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class EnvelopeResponse {
    private Long id;
    private Long budgetId;
    private String name;
    private BigDecimal amount;
    private BigDecimal remainingAmount;
    private Map<String, Object> conditions;
    private LocalDateTime createdAt;

}