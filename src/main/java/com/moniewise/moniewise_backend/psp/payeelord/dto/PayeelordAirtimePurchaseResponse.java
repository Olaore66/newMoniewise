package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Wire response from {@code POST /buy/airtime} — this is the SYNCHRONOUS
 * source-of-truth for whether the purchase happened (the webhook is a secondary
 * audit confirmation that fires only after this response is returned).
 *
 * <p>Documented sample success response:
 * <pre>{@code
 * {
 *   "transaction_id": "UTL9XK4L2P7A",
 *   "api_response": "Airtime purchase completed successfully.",
 *   "network": "MTN",
 *   "balance_before": 15000,
 *   "balance_after": 14500,
 *   "mobile_number": "08012345678",
 *   "amount": "N500",
 *   "status": "successful",
 *   "responseTime": "0.31 secs",
 *   "create_date": "June 7, 2026, 1:14 PM"
 * }
 * }</pre>
 *
 * <p><strong>Quirk:</strong> in the response (unlike the request), {@code amount}
 * comes back as a STRING with a mangled currency prefix — {@code "N500"} rather
 * than a number. We capture it as a raw string for audit/display only; it must
 * NEVER be parsed and trusted as the amount actually charged — that comes from
 * our own {@code PayeelordVasTransaction.faceAmount}, set before we ever call out.
 *
 * <p>Sample error response: {@code {"status": "failed", "message": "Insufficient wallet balance."}}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PayeelordAirtimePurchaseResponse {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("api_response")
    private String apiResponse;

    @JsonProperty("network")
    private String network;

    @JsonProperty("balance_before")
    private BigDecimal balanceBefore;

    @JsonProperty("balance_after")
    private BigDecimal balanceAfter;

    @JsonProperty("mobile_number")
    private String mobileNumber;

    /** Raw string as returned, e.g. "N500" — display/audit only, see class Javadoc. */
    @JsonProperty("amount")
    private String amount;

    /** "successful" | "failed" | "processing" */
    @JsonProperty("status")
    private String status;

    /** Present on error responses — e.g. "Insufficient wallet balance." */
    @JsonProperty("message")
    private String message;

    @JsonProperty("responseTime")
    private String responseTime;

    @JsonProperty("create_date")
    private String createDate;

    public boolean isSuccessful() { return "successful".equalsIgnoreCase(status); }

    public boolean isFailed() { return "failed".equalsIgnoreCase(status); }

    public boolean isProcessing() { return "processing".equalsIgnoreCase(status); }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getTransactionId()              { return transactionId; }
    public void setTransactionId(String v)        { this.transactionId = v; }

    public String getApiResponse()                { return apiResponse; }
    public void setApiResponse(String v)          { this.apiResponse = v; }

    public String getNetwork()                    { return network; }
    public void setNetwork(String network)        { this.network = network; }

    public BigDecimal getBalanceBefore()          { return balanceBefore; }
    public void setBalanceBefore(BigDecimal v)    { this.balanceBefore = v; }

    public BigDecimal getBalanceAfter()           { return balanceAfter; }
    public void setBalanceAfter(BigDecimal v)     { this.balanceAfter = v; }

    public String getMobileNumber()               { return mobileNumber; }
    public void setMobileNumber(String v)         { this.mobileNumber = v; }

    public String getAmount()                     { return amount; }
    public void setAmount(String amount)          { this.amount = amount; }

    public String getStatus()                     { return status; }
    public void setStatus(String status)          { this.status = status; }

    public String getMessage()                    { return message; }
    public void setMessage(String message)        { this.message = message; }

    public String getResponseTime()               { return responseTime; }
    public void setResponseTime(String v)         { this.responseTime = v; }

    public String getCreateDate()                 { return createDate; }
    public void setCreateDate(String createDate)  { this.createDate = createDate; }
}
