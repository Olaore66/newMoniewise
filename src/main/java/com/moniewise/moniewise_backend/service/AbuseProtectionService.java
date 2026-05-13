package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.exception.TooManyRequestsException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AbuseProtectionService {

    public static final String LOGIN = "auth.login";
    public static final String SIGNUP = "auth.signup";
    public static final String SIGNUP_VERIFY = "auth.signup_verify";
    public static final String OTP_GENERATE = "otp.generate";
    public static final String OTP_VERIFY = "otp.verify";
    public static final String FORGOT_PASSWORD = "auth.forgot_password";
    public static final String RESET_VERIFY = "auth.verify_reset_otp";
    public static final String GOOGLE_LOGIN = "auth.google";

    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    public void checkAllowed(String action, String key) {
        AttemptWindow window = attempts.get(compose(action, key));
        if (window == null) return;
        if (window.lockedUntil != null && window.lockedUntil.isAfter(LocalDateTime.now())) {
            long retryAfter = Duration.between(LocalDateTime.now(), window.lockedUntil).getSeconds();
            throw new TooManyRequestsException("Too many attempts. Please wait before trying again.", Math.max(retryAfter, 1));
        }
        if (window.lockedUntil != null && !window.lockedUntil.isAfter(LocalDateTime.now())) {
            attempts.remove(compose(action, key));
        }
    }

    public void recordFailure(String action, String key) {
        String composite = compose(action, key);
        AttemptPolicy policy = policyFor(action);
        attempts.compute(composite, (ignored, existing) -> {
            LocalDateTime now = LocalDateTime.now();
            AttemptWindow state = existing;
            if (state == null || state.windowStartedAt.plus(policy.windowDuration).isBefore(now)) {
                state = new AttemptWindow(now, 0, null);
            }
            state.failures++;
            if (state.failures >= policy.maxAttempts) {
                state.lockedUntil = now.plus(policy.lockoutDuration);
            }
            return state;
        });
    }

    public void recordSuccess(String action, String key) {
        attempts.remove(compose(action, key));
    }

    public String buildKey(String identity, String remoteAddress) {
        String cleanIdentity = identity == null || identity.isBlank() ? "anonymous" : identity.trim().toLowerCase();
        String cleanAddress = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
        return cleanIdentity + "|" + cleanAddress;
    }

    private String compose(String action, String key) {
        return action + "::" + key;
    }

    private AttemptPolicy policyFor(String action) {
        return switch (action) {
            case LOGIN -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
            case SIGNUP -> new AttemptPolicy(5, Duration.ofHours(1), Duration.ofHours(1));
            case SIGNUP_VERIFY -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(30));
            case OTP_GENERATE -> new AttemptPolicy(3, Duration.ofMinutes(10), Duration.ofMinutes(10));
            case OTP_VERIFY -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
            case FORGOT_PASSWORD -> new AttemptPolicy(3, Duration.ofMinutes(15), Duration.ofMinutes(15));
            case RESET_VERIFY -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
            case GOOGLE_LOGIN -> new AttemptPolicy(5, Duration.ofMinutes(10), Duration.ofMinutes(10));
            default -> new AttemptPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
        };
    }

    private static final class AttemptWindow {
        private final LocalDateTime windowStartedAt;
        private int failures;
        private LocalDateTime lockedUntil;

        private AttemptWindow(LocalDateTime windowStartedAt, int failures, LocalDateTime lockedUntil) {
            this.windowStartedAt = windowStartedAt;
            this.failures = failures;
            this.lockedUntil = lockedUntil;
        }
    }

    private record AttemptPolicy(int maxAttempts, Duration windowDuration, Duration lockoutDuration) {}
}