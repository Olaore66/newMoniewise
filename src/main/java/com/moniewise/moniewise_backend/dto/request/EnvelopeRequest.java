package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EnvelopeRequest {
    private String name;
    private BigDecimal percentage; // Changed to BigDecimal for precision
    private Map<String, Object> conditions;// e.g., {"type": "daily", "limit": 500}

    // Add to validate dynamic conditions
    public void validateDynamicConditions() {
        if ("dynamic".equals(conditions.get("type"))) {
            // Add these checks
            if (!conditions.containsKey("disbursementTime")) {
                throw new IllegalArgumentException("Dynamic envelopes require disbursementTime");
            }
            if (!conditions.get("disbursementTime").toString().matches("^([0-1]?\\d|2[0-3]):[0-5]\\d$")) {
                throw new IllegalArgumentException("Invalid time format (HH:mm)");
            }
        }
    }
}