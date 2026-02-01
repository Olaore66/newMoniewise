package com.moniewise.moniewise_backend.dto.request;

import java.math.BigDecimal;

public class WithdrawalRequest {
    private BigDecimal amount;
    private String bankCode;
    private String accountNumber;
    private String accountName; // Resolved name from frontend or backend lookup
    private String password;    // Optional: For transaction PIN validation

    // Getters and Setters
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getBankCode() { return bankCode; }
    public void setBankCode(String bankCode) { this.bankCode = bankCode; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}