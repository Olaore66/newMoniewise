package com.moniewise.moniewise_backend.psp.payeelord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PayeelordDataPurchaseRequest {

    @JsonProperty("networkId")
    private Integer networkId;

    @JsonProperty("dataId")
    private String dataId;

    @JsonProperty("dataType")
    private String dataType;

    @JsonProperty("mobileNumber")
    private String mobileNumber;

    public PayeelordDataPurchaseRequest() {}

    public PayeelordDataPurchaseRequest(String networkId, String dataId, String dataType, String mobileNumber) {
        this.networkId = networkId != null ? Integer.parseInt(networkId) : 0;
        this.dataId = dataId;
        this.dataType = dataType;
        this.mobileNumber = mobileNumber;
    }

    public PayeelordDataPurchaseRequest(Integer networkId, String dataId, String dataType, String mobileNumber) {
        this.networkId = networkId;
        this.dataId = dataId;
        this.dataType = dataType;
        this.mobileNumber = mobileNumber;
    }

    // Getters & Setters
    public Integer getNetworkId() { return networkId; }
    public void setNetworkId(Integer networkId) { this.networkId = networkId; }

    public String getDataId() { return dataId; }
    public void setDataId(String dataId) { this.dataId = dataId; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
}