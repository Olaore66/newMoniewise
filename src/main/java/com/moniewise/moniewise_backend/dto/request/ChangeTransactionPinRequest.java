package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;

@Data
public class ChangeTransactionPinRequest {
    private String currentPin;
    private String newPin;
    private String confirmNewPin;
}
