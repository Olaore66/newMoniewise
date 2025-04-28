package com.moniewise.moniewise_backend.entity;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transaction_logs")
public class TransactionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "budget_id", nullable = true)
    private Long budgetId;

    @Column(name = "source_envelope_id")
    private Long sourceEnvelopeId; // Null for deposits

    @Column(name = "target_envelope_id")
    private Long targetEnvelopeId; // Null for external transfers

    @Column(name = "external_account")
    private String externalAccount; // For transfers to external banks

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "fee")
    private BigDecimal fee;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType; // e.g., "envelope_to_envelope", "envelope_to_external"

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

//    @Column(name = "created_at")
//    private LocalDateTime createdAt = LocalDateTime.now()

    // Constructors
    public TransactionLog() {}

    public TransactionLog(Long userId, Long budgetId, Long sourceEnvelopeId, Long targetEnvelopeId,
                          String externalAccount, BigDecimal amount, BigDecimal fee, String transactionType) {
        this.userId = userId;
        this.budgetId = budgetId;
        this.sourceEnvelopeId = sourceEnvelopeId;
        this.targetEnvelopeId = targetEnvelopeId;
        this.externalAccount = externalAccount;
        this.amount = amount;
        this.fee = fee;
        this.transactionType = transactionType;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getBudgetId() { return budgetId; }
    public void setBudgetId(Long budgetId) { this.budgetId = budgetId; }
    public Long getSourceEnvelopeId() { return sourceEnvelopeId; }
    public void setSourceEnvelopeId(Long sourceEnvelopeId) { this.sourceEnvelopeId = sourceEnvelopeId; }
    public Long getTargetEnvelopeId() { return targetEnvelopeId; }
    public void setTargetEnvelopeId(Long targetEnvelopeId) { this.targetEnvelopeId = targetEnvelopeId; }
    public String getExternalAccount() { return externalAccount; }
    public void setExternalAccount(String externalAccount) { this.externalAccount = externalAccount; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}