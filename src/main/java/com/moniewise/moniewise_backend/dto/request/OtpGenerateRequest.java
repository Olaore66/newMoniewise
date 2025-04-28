package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

// OtpGenerateRequest.java
@Getter
@Setter
public class OtpGenerateRequest {
    @NotBlank
    private String emailOrPhone;
}


