package com.moniewise.moniewise_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ExternalTransferQuoteResponse {

    private Long envelopeId;
    private BigDecimal amount;
    private BigDecimal fee;
    private BigDecimal totalDebit;
    private BigDecimal recipientReceives;
    private String providerName;
    private String feePolicy;
    private String feeSource;
    private String message;
    private String bankName;
    private String accountNumber;
    private String accountName;

    // getters/setters
}