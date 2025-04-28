package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LockRequest {
    private Long envelopeId;
    private String lockType; // "SAFE_LOCK" or "STRICT_LOCK"
    private Integer durationDays;
    private BigDecimal interestRate;
    // Getters & Setters
}
