package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * Request body for the public BVN pre-verification step
 * ({@code POST /auth/bvn/pre-verify}).
 *
 * <p>The user submits their phone number (used to look up the in-progress
 * pending registration in Redis) and their 11-digit BVN.
 */
@Getter
@Setter
public class BvnPreVerifyRequest {

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotBlank(message = "BVN is required")
    @Pattern(regexp = "\\d{11}", message = "BVN must be exactly 11 digits")
    private String bvn;
}
