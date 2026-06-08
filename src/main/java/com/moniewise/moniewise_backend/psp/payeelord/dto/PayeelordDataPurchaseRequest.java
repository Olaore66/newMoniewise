package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire request body for {@code POST /buy/data}.
 *
 * <p>Per the documented sample request:
 * <pre>{@code
 * {
 *   "networkId": "1",
 *   "dataId": "101",
 *   "mobileNumber": "08012345678"
 * }
 * }</pre>
 *
 * <p><strong>Field name quirk:</strong> note this endpoint uses camelCase
 * {@code mobileNumber} — NOT the snake_case {@code mobile_number} that
 * {@link PayeelordAirtimePurchaseRequest} (the airtime endpoint) requires.
 *
 * <p>{@code networkId} and {@code dataId} are looked up server-side from our
 * {@code payeelord_data_plans} catalog (kept fresh by the periodic sync job) —
 * the client only ever sends us the {@code dataId} they picked.
 *
 * <p>The optional {@code cashbackAmount}/{@code cashbackReceived} fields documented
 * by Payeelord are intentionally omitted — cashback is out of scope for this
 * integration.
 */
public class PayeelordDataPurchaseRequest {

    @JsonProperty("networkId")
    private String networkId;

    @JsonProperty("dataId")
    private String dataId;

    @JsonProperty("mobileNumber")
    private String mobileNumber;

    public PayeelordDataPurchaseRequest() {}

    public PayeelordDataPurchaseRequest(String networkId, String dataId, String mobileNumber) {
        this.networkId = networkId;
        this.dataId = dataId;
        this.mobileNumber = mobileNumber;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getNetworkId()              { return networkId; }
    public void setNetworkId(String v)        { this.networkId = v; }

    public String getDataId()                 { return dataId; }
    public void setDataId(String dataId)      { this.dataId = dataId; }

    public String getMobileNumber()           { return mobileNumber; }
    public void setMobileNumber(String v)     { this.mobileNumber = v; }
}
