package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

public class WithdrawalRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "100.0", message = "Minimum withdrawal is ₦100")
    private BigDecimal amount;

    @NotBlank(message = "Transaction PIN is required")
    private String transactionPin;

    // Getters and Setters
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTransactionPin() { return transactionPin; }
    public void setTransactionPin(String transactionPin) { this.transactionPin = transactionPin; }
}