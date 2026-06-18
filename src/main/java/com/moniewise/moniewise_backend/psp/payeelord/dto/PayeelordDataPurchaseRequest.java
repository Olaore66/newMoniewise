package com.moniewise.moniewise_backend.psp.payeelord.dto;
//
//import com.fasterxml.jackson.annotation.JsonProperty;
//
//public class PayeelordDataPurchaseRequest {
//
//    @JsonProperty("networkId")
//    private Integer networkId;
//
//    @JsonProperty("plan")           // ← Changed from dataId to "plan"
//    private String plan;
//
//    @JsonProperty("dataType")
//    private String dataType;
//
//    @JsonProperty("mobileNumber")
//    private String mobileNumber;
//
//    public PayeelordDataPurchaseRequest() {}
//
//    public PayeelordDataPurchaseRequest(String networkId, String dataId, String dataType, String mobileNumber) {
//        this.networkId = networkId != null ? Integer.parseInt(networkId) : 0;
//        this.plan = dataId;
//        this.dataType = dataType;
//        this.mobileNumber = mobileNumber;
//    }
//
//    public PayeelordDataPurchaseRequest(Integer networkId, String dataId, String dataType, String mobileNumber) {
//        this.networkId = networkId;
//        this.plan = dataId;
//        this.dataType = dataType;
//        this.mobileNumber = mobileNumber;
//    }
//
//    // Getters & Setters
//    public Integer getNetworkId() { return networkId; }
//    public void setNetworkId(Integer networkId) { this.networkId = networkId; }
//
//    public String getDataId() { return plan; }
//    public void setDataId(String dataId) { this.plan = dataId; }
//
//    public String getDataType() { return dataType; }
//    public void setDataType(String dataType) { this.dataType = dataType; }
//
//    public String getMobileNumber() { return mobileNumber; }
//    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
//}

import com.fasterxml.jackson.annotation.JsonProperty;

public class PayeelordDataPurchaseRequest {

    @JsonProperty("network")
    private Integer network;

    @JsonProperty("networkId")
    private Integer networkId;

    @JsonProperty("plan")
    private String plan;

    @JsonProperty("dataId")
    private String dataId;

    @JsonProperty("dataType")
    private String dataType;

    @JsonProperty("mobileNumber")
    private String mobileNumber;

    public PayeelordDataPurchaseRequest() {}

    public PayeelordDataPurchaseRequest(Integer networkId, String dataId, String dataType, String mobileNumber) {
        this.network = networkId;
        this.networkId = networkId;
        this.plan = dataId;
        this.dataId = dataId;
        this.dataType = dataType;
        this.mobileNumber = mobileNumber;
    }

    // Getters & Setters
    public Integer getNetwork() { return network; }
    public void setNetwork(Integer network) { this.network = network; }

    public Integer getNetworkId() { return networkId; }
    public void setNetworkId(Integer networkId) { this.networkId = networkId; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public String getDataId() { return dataId; }
    public void setDataId(String dataId) { this.dataId = dataId; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
}