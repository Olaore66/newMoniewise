package com.moniewise.moniewise_backend.dto;

import com.moniewise.moniewise_backend.dto.response.BvnVerificationResultDto;

import java.io.Serializable;

/**
 * Holds the signup data of a user who has submitted the registration form but
 * has not yet verified their OTP.  Stored in Redis with a TTL so unverified
 * registrations are automatically discarded and never pollute the database.
 *
 * <p>After the user completes the optional BVN pre-verify step
 * ({@code POST /auth/bvn/pre-verify}), the {@link #bvn} and
 * {@link #bvnVerificationResult} fields are populated so they can be
 * persisted to PostgreSQL when the OTP is verified and the user record is
 * created.
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

    /**
     * The raw 11-digit BVN submitted during the pre-verify step.
     * {@code null} if the user skipped BVN verification during signup.
     */
    private String bvn;

    /**
     * The full structured result returned by SecureWave during the pre-verify step.
     * {@code null} if BVN pre-verification has not yet been completed.
     */
    private BvnVerificationResultDto bvnVerificationResult;

    // ── No-arg constructor required for Jackson deserialization ──────────────
    public PendingRegistrationData() {}

    /**
     * Phone-free constructor used for new signups where the phone number is
     * collected later during profile completion.  The {@code phone} field is
     * left {@code null} and will be populated by {@code PUT /users/profile}.
     */
    public PendingRegistrationData(String email,
                                   String encodedPassword,
                                   String otpCode,
                                   long otpExpiresAtEpochMillis) {
        this.email = email;
        this.phone = null;
        this.encodedPassword = encodedPassword;
        this.otpCode = otpCode;
        this.otpExpiresAtEpochMillis = otpExpiresAtEpochMillis;
    }

    /**
     * @deprecated Phone is no longer collected at signup — use the 4-arg constructor.
     *             Kept for backward-compat deserialization of Redis entries written
     *             by previous app versions.
     */
    @Deprecated
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

    public String getBvn() { return bvn; }
    public void setBvn(String bvn) { this.bvn = bvn; }

    public BvnVerificationResultDto getBvnVerificationResult() { return bvnVerificationResult; }
    public void setBvnVerificationResult(BvnVerificationResultDto bvnVerificationResult) {
        this.bvnVerificationResult = bvnVerificationResult;
    }
}
