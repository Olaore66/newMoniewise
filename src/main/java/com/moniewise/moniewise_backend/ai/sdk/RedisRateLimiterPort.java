package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.port.RateLimiterPort;
import com.moniewise.moniewise_backend.exception.TooManyRequestsException;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiterPort implements RateLimiterPort {

    private final AbuseProtectionService abuse;

    public RedisRateLimiterPort(AbuseProtectionService abuse) {
        this.abuse = abuse;
    }

    @Override
    public Verdict check(String bucket, String subjectKey) {
        try {
            abuse.checkAllowed(bucket, subjectKey);
            return Verdict.allowed(bucket);
        } catch (TooManyRequestsException e) {
            return Verdict.blocked(bucket, (int) e.getRetryAfterSeconds());
        } catch (RuntimeException e) {
            return Verdict.allowed(bucket);
        }
    }

    @Override
    public void record(String bucket, String subjectKey) {
        abuse.recordRequest(bucket, subjectKey);
    }
}
