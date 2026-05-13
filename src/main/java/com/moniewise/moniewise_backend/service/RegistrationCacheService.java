package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.PendingRegistrationData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stores pending (pre-OTP-verification) registrations in Redis with a TTL.
 *
 * <p>Key format: {@code pending_registration:{email}}
 * <br>Default TTL: {@value #TTL_MINUTES} minutes — long enough for a user to receive
 * and submit their OTP, short enough to keep Redis lean.
 *
 * <p>Nothing is written to PostgreSQL until the OTP is successfully verified.
 */
@Service
public class RegistrationCacheService {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationCacheService.class);

    static final String KEY_PREFIX = "pending_registration:";
    /** How long a pending registration lives in Redis. */
    static final long TTL_MINUTES = 10L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RegistrationCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists {@code data} to Redis under the key {@code pending_registration:{email}}
     * with a {@value #TTL_MINUTES}-minute TTL.  Any existing entry for the same email
     * is overwritten (handles "resend OTP" requests).
     *
     * @throws RuntimeException if serialization or Redis write fails
     */
    public void save(PendingRegistrationData data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + data.getEmail().toLowerCase(),
                    json,
                    TTL_MINUTES,
                    TimeUnit.MINUTES
            );
            logger.debug("Cached pending registration for {}", data.getEmail());
        } catch (Exception e) {
            logger.error("Failed to cache pending registration for {}", data.getEmail(), e);
            throw new RuntimeException("Temporary storage unavailable. Please try again.", e);
        }
    }

    /**
     * Retrieves and deserializes the pending registration for {@code email}.
     *
     * @return an empty Optional if the key has expired or was never created
     */
    public Optional<PendingRegistrationData> findByEmail(String email) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + email.toLowerCase());
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, PendingRegistrationData.class));
        } catch (Exception e) {
            logger.error("Failed to retrieve pending registration for {}", email, e);
            return Optional.empty();
        }
    }

    /** Removes the pending registration entry. Call this after successful OTP verification. */
    public void delete(String email) {
        redisTemplate.delete(KEY_PREFIX + email.toLowerCase());
    }

    /** Returns {@code true} if a pending registration key exists (has not expired). */
    public boolean exists(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + email.toLowerCase()));
    }
}
