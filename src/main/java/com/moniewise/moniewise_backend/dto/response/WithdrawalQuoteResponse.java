package com.moniewise.moniewise_backend.dto.response;

import java.math.BigDecimal;

public class WithdrawalQuoteResponse {

    private BigDecimal withdrawalAmount;
    /**
     * Moniewise markup fee only. This is what enters the revenue wallet.
     * Does NOT include the NIP bank charge.
     */
    private BigDecimal fee;
    /**
     * NIBSS NIP interbank fee charged by Rubies at the BaaS level.
     * Goes to Rubies / the banking system — Moniewise does NOT collect this.
     * Zero for non-Rubies providers (they handle fees differently).
     */
    private BigDecimal bankCharge;
    /** = withdrawalAmount + bankCharge + fee */
    private BigDecimal totalDebit;
    private BigDecimal recipientReceives;
    private String feePolicy;
    private String feeSource;
    private String message;

    public WithdrawalQuoteResponse() {
    }

    /** Full constructor including the NIP bank charge split. */
    public WithdrawalQuoteResponse(BigDecimal withdrawalAmount,
                                   BigDecimal fee,
                                   BigDecimal bankCharge,
                                   BigDecimal totalDebit,
                                   BigDecimal recipientReceives,
                                   String feePolicy,
                                   String feeSource,
                                   String message) {
        this.withdrawalAmount = withdrawalAmount;
        this.fee = fee;
        this.bankCharge = bankCharge;
        this.totalDebit = totalDebit;
        this.recipientReceives = recipientReceives;
        this.feePolicy = feePolicy;
        this.feeSource = feeSource;
        this.message = message;
    }

    /** Backward-compatible constructor (bankCharge defaults to ZERO — for legacy paths). */
    public WithdrawalQuoteResponse(BigDecimal withdrawalAmount,
                                   BigDecimal fee,
                                   BigDecimal totalDebit,
                                   BigDecimal recipientReceives,
                                   String feePolicy,
                                   String feeSource,
                                   String message) {
        this(withdrawalAmount, fee, BigDecimal.ZERO, totalDebit,
                recipientReceives, feePolicy, feeSource, message);
    }

    public BigDecimal getWithdrawalAmount() {
        return withdrawalAmount;
    }

    public void setWithdrawalAmount(BigDecimal withdrawalAmount) {
        this.withdrawalAmount = withdrawalAmount;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public BigDecimal getBankCharge() {
        return bankCharge != null ? bankCharge : BigDecimal.ZERO;
    }

    public void setBankCharge(BigDecimal bankCharge) {
        this.bankCharge = bankCharge;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public void setTotalDebit(BigDecimal totalDebit) {
        this.totalDebit = totalDebit;
    }

    public BigDecimal getRecipientReceives() {
        return recipientReceives;
    }

    public void setRecipientReceives(BigDecimal recipientReceives) {
        this.recipientReceives = recipientReceives;
    }

    public String getFeePolicy() {
        return feePolicy;
    }

    public void setFeePolicy(String feePolicy) {
        this.feePolicy = feePolicy;
    }

    public String getFeeSource() {
        return feeSource;
    }

    public void setFeeSource(String feeSource) {
        this.feeSource = feeSource;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
