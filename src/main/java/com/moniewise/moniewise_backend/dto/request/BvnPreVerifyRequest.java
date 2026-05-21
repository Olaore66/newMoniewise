package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * Request body for the public BVN pre-verification step
 * ({@code POST /auth/bvn/pre-verify}).
 *
 * <p>The user submits their email (used to look up the in-progress pending
 * registration in Redis) and their 11-digit BVN.  The phone number is
 * retrieved from Redis so the user doesn't have to type it again.
 */
@Getter
@Setter
public class BvnPreVerifyRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    @NotBlank(message = "BVN is required")
    @Pattern(regexp = "\\d{11}", message = "BVN must be exactly 11 digits")
    private String bvn;
}
