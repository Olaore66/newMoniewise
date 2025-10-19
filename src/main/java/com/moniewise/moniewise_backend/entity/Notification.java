package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notifications")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @Column(name = "budget_id")
    private Long budgetId;

    @Column(name = "envelope_id")
    private Long envelopeId;

    @Column(name = "action_type")
    private String actionType; // e.g., "VIEW_ENVELOPE", "CLAIM_DISBURSEMENT", "VIEW_BUDGET"

    @Column(name = "redirect_url")
    private String redirectUrl; // e.g., "/budgets/60/envelopes/543", "/disbursements/34164"
}