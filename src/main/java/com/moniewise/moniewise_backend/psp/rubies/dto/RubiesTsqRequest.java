package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /{stage}/baas-transaction/tsq
 * (Transaction Status Query)
 */
public class RubiesTsqRequest {

    @JsonProperty("transactionReference")
    private String transactionReference;

    public RubiesTsqRequest() {}

    public RubiesTsqRequest(String transactionReference) {
        this.transactionReference = transactionReference;
    }

    public String getTransactionReference()           { return transactionReference; }
    public void setTransactionReference(String v)     { this.transactionReference = v; }
}
