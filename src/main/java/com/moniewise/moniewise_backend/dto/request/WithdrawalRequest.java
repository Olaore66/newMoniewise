package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

public class WithdrawalRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "100.0", message = "Minimum withdrawal is ₦100")
    private BigDecimal amount;

    @NotBlank(message = "Transaction PIN is required")
    private String transactionPin;

    @Size(max = 120, message = "Narration must not exceed 120 characters")
    private String narration;

    // Getters and Setters
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTransactionPin() { return transactionPin; }
    public void setTransactionPin(String transactionPin) { this.transactionPin = transactionPin; }

    public String getNarration() { return narration; }
    public void setNarration(String narration) { this.narration = narration; }
}
