package com.moniewise.moniewise_backend.dto;

import java.io.Serializable;

/**
 * Holds the signup data of a user who has submitted the registration form but
 * has not yet verified their OTP.  Stored in Redis with a TTL so unverified
 * registrations are automatically discarded and never pollute the database.
 */
public class PendingRegistrationData implements Serializable {

    private String email;
    private String phone;
    /** BCrypt-encoded password — never store plain-text. */
    private String encodedPassword;
    /** The 6-digit OTP code that was emailed to the user. */
    private String otpCode;
    /** Epoch-millis at which the OTP expires (5 minutes after creation). */
    private long otpExpiresAtEpochMillis;

    // ── No-arg constructor required for Jackson deserialization ──────────────
    public PendingRegistrationData() {}

    public PendingRegistrationData(String email,
                                   String phone,
                                   String encodedPassword,
                                   String otpCode,
                                   long otpExpiresAtEpochMillis) {
        this.email = email;
        this.phone = phone;
        this.encodedPassword = encodedPassword;
        this.otpCode = otpCode;
        this.otpExpiresAtEpochMillis = otpExpiresAtEpochMillis;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEncodedPassword() { return encodedPassword; }
    public void setEncodedPassword(String encodedPassword) { this.encodedPassword = encodedPassword; }

    public String getOtpCode() { return otpCode; }
    public void setOtpCode(String otpCode) { this.otpCode = otpCode; }

    public long getOtpExpiresAtEpochMillis() { return otpExpiresAtEpochMillis; }
    public void setOtpExpiresAtEpochMillis(long otpExpiresAtEpochMillis) {
        this.otpExpiresAtEpochMillis = otpExpiresAtEpochMillis;
    }
}
