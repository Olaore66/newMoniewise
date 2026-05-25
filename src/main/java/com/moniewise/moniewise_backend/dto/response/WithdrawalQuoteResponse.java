package com.moniewise.moniewise_backend.dto.response;

import java.math.BigDecimal;

public class WithdrawalQuoteResponse {

    private BigDecimal withdrawalAmount;
    private BigDecimal fee;
    private BigDecimal totalDebit;
    private BigDecimal recipientReceives;
    private String feePolicy;
    private String feeSource;
    private String message;

    public WithdrawalQuoteResponse() {
    }

    public WithdrawalQuoteResponse(BigDecimal withdrawalAmount,
                                   BigDecimal fee,
                                   BigDecimal totalDebit,
                                   BigDecimal recipientReceives,
                                   String feePolicy,
                                   String feeSource,
                                   String message) {
        this.withdrawalAmount = withdrawalAmount;
        this.fee = fee;
        this.totalDebit = totalDebit;
        this.recipientReceives = recipientReceives;
        this.feePolicy = feePolicy;
        this.feeSource = feeSource;
        this.message = message;
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
