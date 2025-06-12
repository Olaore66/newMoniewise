package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//public class EnvelopeRequest {
//    private String name;
//    private BigDecimal percentage; // Changed to BigDecimal for precision
//    private Map<String, Object> conditions;// e.g., {"type": "daily", "limit": 500}
//
//    // Add to validate dynamic conditions
////    public void validateDynamicConditions() {
////        if ("dynamic".equals(conditions.get("type"))) {
////            // Add these checks
////            if (!conditions.containsKey("disbursementTime")) {
////                throw new IllegalArgumentException("Dynamic envelopes require disbursementTime");
////            }
////            if (!conditions.get("disbursementTime").toString().matches("^([0-1]?\\d|2[0-3]):[0-5]\\d$")) {
////                throw new IllegalArgumentException("Invalid time format (HH:mm)");
////            }
////        }
////    }
//
//    // In EnvelopeRequest class
//    public void validateDynamicConditions() {
//        // Ensure this method doesn't remove or alter 'limit'
//        if (!conditions.containsKey("intervalDays") || !conditions.containsKey("startDate") || !conditions.containsKey("disbursementTime")) {
//            throw new IllegalArgumentException("Dynamic conditions must include intervalDays, startDate, and disbursementTime");
//        }
//        // Add limit validation if needed
//        if (!conditions.containsKey("limit") || !(conditions.get("limit") instanceof Number) || ((Number) conditions.get("limit")).doubleValue() <= 0) {
//            throw new IllegalArgumentException("Dynamic envelope requires a positive limit");
//        }
//        if (!conditions.get("disbursementTime").toString().matches("^([0-1]?\\d|2[0-3]):[0-5]\\d$")) {
//                throw new IllegalArgumentException("Invalid time format (HH:mm)");
//            }
//    }
//}

import java.math.BigDecimal;
import java.util.Map;
import java.time.DayOfWeek;
import java.util.List;

public class EnvelopeRequest {
    private String name;
    private BigDecimal percentage;
    private Map<String, Object> conditions;

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
    public Map<String, Object> getConditions() { return conditions; }
    public void setConditions(Map<String, Object> conditions) { this.conditions = conditions; }

    public void validateDynamicConditions() {
        if (conditions == null || conditions.get("type") == null || !"dynamic".equals(conditions.get("type"))) {
            throw new IllegalArgumentException("Invalid dynamic conditions: type must be 'dynamic'");
        }
        @SuppressWarnings("unchecked")
        List<String> days = (List<String>) conditions.getOrDefault("days", List.of());
        if (days.isEmpty()) {
            throw new IllegalArgumentException("Dynamic conditions must include at least one day");
        }
        // Validate days
        for (String day : days) {
            try {
                DayOfWeek.valueOf(day.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid day in dynamic conditions: " + day);
            }
        }
        Object limit = conditions.get("limit");
        if (limit == null || !(limit instanceof Number) || ((Number) limit).doubleValue() <= 0) {
            throw new IllegalArgumentException("Dynamic conditions must include a positive limit");
        }
        Object disbursementTime = conditions.get("disbursementTime");
        if (disbursementTime == null || !disbursementTime.toString().matches("\\d{2}:\\d{2}")) {
            throw new IllegalArgumentException("Dynamic conditions must include valid disbursementTime (HH:mm)");
        }
    }
}