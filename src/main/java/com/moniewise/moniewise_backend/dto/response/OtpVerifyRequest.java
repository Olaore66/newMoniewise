package com.moniewise.moniewise_backend.dto.response;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

// OtpVerifyRequest.java
@Getter
@Setter
public class OtpVerifyRequest {
    @NotBlank
    private String emailOrPhone;

    @NotBlank
    @Size(min = 6, max = 6)
    private String otpCode;
}