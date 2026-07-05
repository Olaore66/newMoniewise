package com.moniewise.moniewise_backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "savings_goals")
@Data
@NoArgsConstructor
public class SavingsGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 🔗 THE RELATIONAL LINK
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore // Prevents infinite loops when sending JSON to the frontend
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal targetAmount;

    @Column(nullable = false)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal interestRate = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SavingsStatus status = SavingsStatus.ACTIVE;

    /** Set once the "maturing in a week" reminder (email + push) has gone out, so the job never sends it twice. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean maturityReminderSent = false;

    /** Set once the "matures tomorrow" reminder (email + push) has gone out — separate flag from the 7-day one. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean maturityEveReminderSent = false;

    /**
     * Last calendar day (Africa/Lagos) the lifecycle job processed this goal.
     * Lets the job run every few hours (self-healing after missed runs / instance
     * sleep) while guaranteeing interest is applied at most once per day.
     */
    @Column(name = "last_processed_date")
    private LocalDate lastProcessedDate;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}