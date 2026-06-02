package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Response from POST /{stage}/baas-transaction/fund-transfer
 *
 * <p>Response codes:
 * <ul>
 *   <li>{@code "00"} — success / settled</li>
 *   <li>{@code "09"}, {@code "90"}, {@code "99"} — pending (poll TSQ)</li>
 *   <li>anything else — failed</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesFundTransferResponse {

    private static final Set<String> PENDING_CODES = Set.of("09", "90", "99");

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    @JsonProperty("data")
    private Data data;

    public boolean isSuccess() {
        return "00".equals(responseCode);
    }

    public boolean isPending() {
        return PENDING_CODES.contains(responseCode);
    }

    public boolean isFailed() {
        return !isSuccess() && !isPending();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getResponseCode()            { return responseCode; }
    public void setResponseCode(String v)      { this.responseCode = v; }

    public String getResponseMessage()         { return responseMessage; }
    public void setResponseMessage(String v)   { this.responseMessage = v; }

    public Data getData()                      { return data; }
    public void setData(Data v)                { this.data = v; }

    // ── Nested ────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {

        @JsonProperty("transactionReference")
        private String transactionReference;

        @JsonProperty("sessionId")
        private String sessionId;

        @JsonProperty("amount")
        private String amount;

        @JsonProperty("narration")
        private String narration;

        public String getTransactionReference()        { return transactionReference; }
        public void setTransactionReference(String v)  { this.transactionReference = v; }

        public String getSessionId()                   { return sessionId; }
        public void setSessionId(String v)             { this.sessionId = v; }

        public String getAmount()                      { return amount; }
        public void setAmount(String v)                { this.amount = v; }

        public String getNarration()                   { return narration; }
        public void setNarration(String v)             { this.narration = v; }
    }
}
