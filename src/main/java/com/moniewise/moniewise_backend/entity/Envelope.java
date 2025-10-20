package com.moniewise.moniewise_backend.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Getter
@Setter
@NoArgsConstructor
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
@Table(name = "envelopes")
public class Envelope {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false)
    private Budget budget;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "remaining_amount", nullable = false)
    private BigDecimal remainingAmount; // Tracks current period's remaining limit

    @Column(name = "total_remaining_amount", nullable = false)
    private BigDecimal totalRemainingAmount; // Tracks total unspent balance

    @Convert(disableConversion = true) // Disable auto-converter
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditions;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_accessed")
    private LocalDateTime lastAccessed;

    @Column(name = "last_disbursed_at")
    private LocalDateTime lastDisbursedAt;

    @Column(name = "next_disbursement_at")
    private LocalDateTime nextDisbursementAt;

    @Column(name = "matured_at")
    private LocalDateTime maturedAt;

    @Column(name = "has_matured")
    private Boolean hasMatured;

    private static final ObjectMapper mapper = new ObjectMapper();

    public Envelope(Budget budget, String name, BigDecimal amount, Map<String, Object> conditions) {
        this.budget = budget;
        this.name = name;
        this.amount = amount;
        this.totalRemainingAmount = amount;
        this.remainingAmount = getPeriodLimit(conditions);
        this.conditions = conditions;
        this.createdAt = LocalDateTime.now();
        this.hasMatured = false;
    }

    private BigDecimal getPeriodLimit(Map<String, Object> conditions) {
        if (conditions != null && conditions.containsKey("limit") && conditions.get("limit") instanceof Number) {
            return new BigDecimal(((Number) conditions.get("limit")).doubleValue());
        }
        return BigDecimal.ZERO;
    }
}