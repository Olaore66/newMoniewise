package com.moniewise.moniewise_backend.enums;

public enum NotificationType {
    MATURITY_ALERT,
    GRACE_PERIOD_REMINDER,
    EXPIRATION_NOTICE,
    BUDGET_COMPLETED,
    WELCOME,
    GENERAL,
    BUDGET_END,
    PRE_DISBURSEMENT,
    DISBURSEMENT,
    EXPIRED_DISBURSEMENT,
    ENVELOPE_TRANSFER,
    EXTERNAL_TRANSFER,
    INSUFFICIENT_BALANCE,
    BUDGET_CREATION_FEE,
    WALLET_FUNDED,

    BUDGET_CREATION,
    ENVELOPE_CREATED,
    ENVELOPE_UPDATED,
    ENVELOPE_DELETED,

    // NEW — BEST PRACTICES
    BUDGET_END_SOON,           // 3 days left
    DISBURSEMENT_SUCCESS,      // Money released
    DISBURSEMENT_FAILED,       // Grace period expired
    LIMIT_REACHED,             // Daily/weekly cap
    EMERGENCY_USED,
    LOCK_EXPIRED,              // Strict lock → refund
    WEEKLY_SUMMARY,
    POSITIVE_NUDGE,



    }
