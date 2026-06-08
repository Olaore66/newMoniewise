package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Wire response from {@code POST /buy/data} — synchronous source-of-truth for
 * whether the data purchase happened (see {@link PayeelordAirtimePurchaseResponse}
 * for the broader synchronous-confirmation note, which applies identically here).
 *
 * <p>Documented sample success response:
 * <pre>{@code
 * {
 *   "transaction_id": "UTL9XK4L2P7A",
 *   "api_response": "Purchase completed successfully.",
 *   "plan_network": "MTN",
 *   "balance_before": 15000,
 *   "balance_after": 14491,
 *   "mobile_number": "08012345678",
 *   "plan_name": "1GB SME",
 *   "plan_amount": "N509",
 *   "status": "successful",
 *   "Status": "successful",
 *   "responseTime": "0.42 secs",
 *   "create_date": "June 7, 2026, 1:14 PM"
 * }
 * }</pre>
 *
 * <p><strong>Quirks:</strong>
 * <ul>
 *   <li>{@code plan_amount} comes back as a mangled-currency STRING (e.g. {@code "N509"})
 *       — same deal as airtime's {@code amount}: audit/display only, never parsed
 *       as the authoritative price. That's {@code PayeelordDataPlan.costPrice}
 *       (scraper-owned) plus our own markup.</li>
 *   <li>The sample response contains BOTH {@code "status"} and {@code "Status"}
 *       (case-duplicated key) — {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 *       plus mapping only the lowercase one is the safe, deterministic choice;
 *       Jackson would otherwise non-deterministically pick whichever key it sees
 *       last when both map to the same field.</li>
 * </ul>
 *
 * <p>Sample error response: {@code {"status": "failed", "message": "Sorry, this plan is not available at the moment."}}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PayeelordDataPurchaseResponse {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("api_response")
    private String apiResponse;

    @JsonProperty("plan_network")
    private String planNetwork;

    @JsonProperty("balance_before")
    private BigDecimal balanceBefore;

    @JsonProperty("balance_after")
    private BigDecimal balanceAfter;

    @JsonProperty("mobile_number")
    private String mobileNumber;

    @JsonProperty("plan_name")
    private String planName;

    /** Raw string as returned, e.g. "N509" — display/audit only, see class Javadoc. */
    @JsonProperty("plan_amount")
    private String planAmount;

    /** "successful" | "failed" | "processing" — deliberately ignores the duplicated "Status" key. */
    @JsonProperty("status")
    private String status;

    /** Present on error responses — e.g. "Sorry, this plan is not available at the moment." */
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

    public String getPlanNetwork()                { return planNetwork; }
    public void setPlanNetwork(String v)          { this.planNetwork = v; }

    public BigDecimal getBalanceBefore()          { return balanceBefore; }
    public void setBalanceBefore(BigDecimal v)    { this.balanceBefore = v; }

    public BigDecimal getBalanceAfter()           { return balanceAfter; }
    public void setBalanceAfter(BigDecimal v)     { this.balanceAfter = v; }

    public String getMobileNumber()               { return mobileNumber; }
    public void setMobileNumber(String v)         { this.mobileNumber = v; }

    public String getPlanName()                   { return planName; }
    public void setPlanName(String planName)      { this.planName = planName; }

    public String getPlanAmount()                 { return planAmount; }
    public void setPlanAmount(String planAmount)  { this.planAmount = planAmount; }

    public String getStatus()                     { return status; }
    public void setStatus(String status)          { this.status = status; }

    public String getMessage()                    { return message; }
    public void setMessage(String message)        { this.message = message; }

    public String getResponseTime()               { return responseTime; }
    public void setResponseTime(String v)         { this.responseTime = v; }

    public String getCreateDate()                 { return createDate; }
    public void setCreateDate(String createDate)  { this.createDate = createDate; }
}
