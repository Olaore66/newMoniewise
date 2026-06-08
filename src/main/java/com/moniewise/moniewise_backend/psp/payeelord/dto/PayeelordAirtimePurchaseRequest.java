package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Wire request body for {@code POST /buy/airtime}.
 *
 * <p><strong>Field name quirk:</strong> Payeelord's airtime endpoint uses
 * snake_case for the recipient field — {@code mobile_number} — while its data
 * endpoint (see {@link PayeelordDataPurchaseRequest}) uses camelCase
 * {@code mobileNumber}. This isn't a typo on our side; it's how their two
 * controllers are documented. Sending the wrong casing to the wrong endpoint
 * means the field is silently dropped and the request fails validation upstream.
 *
 * <p>Per the documented sample request, {@code amount} is sent as a JSON NUMBER
 * (e.g. {@code "amount": 500}) — unlike Rubies, which requires amounts as strings.
 */
public class PayeelordAirtimePurchaseRequest {

    /** MTN | GLO | AIRTEL | 9MOBILE */
    @JsonProperty("network")
    private String network;

    @JsonProperty("mobile_number")
    private String mobileNumber;

    /** Naira amount, 10–5000 inclusive — sent as a number, not a string. */
    @JsonProperty("amount")
    private BigDecimal amount;

    public PayeelordAirtimePurchaseRequest() {}

    public PayeelordAirtimePurchaseRequest(String network, String mobileNumber, BigDecimal amount) {
        this.network = network;
        this.mobileNumber = mobileNumber;
        this.amount = amount;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getNetwork()                   { return network; }
    public void setNetwork(String network)       { this.network = network; }

    public String getMobileNumber()               { return mobileNumber; }
    public void setMobileNumber(String v)         { this.mobileNumber = v; }

    public BigDecimal getAmount()                 { return amount; }
    public void setAmount(BigDecimal amount)      { this.amount = amount; }
}
