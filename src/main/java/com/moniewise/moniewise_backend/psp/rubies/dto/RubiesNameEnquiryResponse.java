package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from POST /{stage}/baas-transaction/name-enquiry
 *
 * <p>On success:
 * <pre>
 * {
 *   "responseCode": "00",
 *   "responseMessage": "success",
 *   "data": {
 *     "accountName": "JOHN DOE",
 *     "accountNumber": "1234567890",
 *     "bankCode": "058",
 *     "bankName": "GTBank"
 *   }
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesNameEnquiryResponse {

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    @JsonProperty("data")
    private Data data;

    public boolean isSuccess() {
        return "00".equals(responseCode);
    }

    public String getResponseCode()            { return responseCode; }
    public void setResponseCode(String v)      { this.responseCode = v; }

    public String getResponseMessage()         { return responseMessage; }
    public void setResponseMessage(String v)   { this.responseMessage = v; }

    public Data getData()                      { return data; }
    public void setData(Data v)                { this.data = v; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {

        @JsonProperty("accountName")
        private String accountName;

        @JsonProperty("accountNumber")
        private String accountNumber;

        @JsonProperty("bankCode")
        private String bankCode;

        @JsonProperty("bankName")
        private String bankName;

        public String getAccountName()           { return accountName; }
        public void setAccountName(String v)     { this.accountName = v; }

        public String getAccountNumber()         { return accountNumber; }
        public void setAccountNumber(String v)   { this.accountNumber = v; }

        public String getBankCode()              { return bankCode; }
        public void setBankCode(String v)        { this.bankCode = v; }

        public String getBankName()              { return bankName; }
        public void setBankName(String v)        { this.bankName = v; }
    }
}
