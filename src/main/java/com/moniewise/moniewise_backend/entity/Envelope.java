package com.moniewise.moniewise_backend.entity;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.Getter;
import lombok.Setter;

import lombok.AllArgsConstructor;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.*;
import java.math.BigDecimal;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Getter
@Setter
//@NoArgsConstructor
@AllArgsConstructor

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
    private BigDecimal remainingAmount;

    @Convert(disableConversion = true) // Disable auto-converter
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditions;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_accessed")
    private LocalDateTime lastAccessed;

//    @OneToMany(mappedBy = "envelope", cascade = CascadeType.ALL, orphanRemoval = true)
//    private List<TransactionLog> transactionLogs = new ArrayList<>();

    private static final ObjectMapper mapper = new ObjectMapper();

    // Constructors
    public Envelope() {}
    public Envelope(Budget budget, String name, BigDecimal amount, Map<String, Object> conditions) {
        this.budget = budget;
        this.name = name;
        this.amount = amount;
        this.remainingAmount = amount;
        this.conditions = conditions; // ✅ Directly assign the Map
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Budget getBudget() { return budget; }
    public void setBudget(Budget budget) { this.budget = budget; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getRemainingAmount() { return remainingAmount; }
    public void setRemainingAmount(BigDecimal remainingAmount) { this.remainingAmount = remainingAmount; }

    public Map<String, Object> getConditions() {
        return conditions;
    }

    public void setConditions(Map<String, Object> conditions) {
        this.conditions = conditions;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

}
