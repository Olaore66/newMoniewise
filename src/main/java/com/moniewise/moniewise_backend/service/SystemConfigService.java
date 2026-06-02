package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.SystemConfig;
import com.moniewise.moniewise_backend.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Typed access to the {@code system_config} table.
 *
 * <p>All values are cached in Redis with a 5-minute TTL. Cache is evicted on
 * every {@link #set} call. Falls back to DB when Redis is unreachable
 * (fail-open — same pattern as AbuseProtectionService).
 *
 * <h3>Well-known keys</h3>
 * Use the public constants below to avoid string literals scattered across the codebase.
 */
@Service
public class SystemConfigService {

    private static final Logger logger = LoggerFactory.getLogger(SystemConfigService.class);
    private static final String CACHE_PREFIX = "syscfg:";
    private static final Duration CACHE_TTL   = Duration.ofMinutes(5);

    // ── Well-known config keys ─────────────────────────────────────────────────
    /** Active PSP: "PROVIDUS", "SECUREWAVE", or "RUBIES" */
    public static final String PSP_ACTIVE            = "psp.active";
    /** Rubies path parameter: "dev" or "prod" */
    public static final String RUBIES_STAGE          = "rubies.stage";
    /**
     * Moniewise's own Rubies MFB account number — the wallet that receives the
     * markup fee on every successful Rubies transfer.
     *
     * <p>Set via: {@code PUT /admin/config/rubies.revenue.account.number}
     * with body {@code {"value":"7012345678"}}.
     *
     * <p>Until this is configured, the markup fee is tracked in the internal
     * revenue wallet only (no actual Rubies transfer is made for the fee).
     */
    public static final String RUBIES_REVENUE_ACCOUNT_NUMBER = "rubies.revenue.account.number";
    /** Display name for Moniewise's Rubies revenue wallet (used in narration). */
    public static final String RUBIES_REVENUE_ACCOUNT_NAME   = "rubies.revenue.account.name";

    /** Tier 1 transfer upper bound (NGN) — transfers ≤ this value use tier1 fee */
    public static final String MARKUP_TIER1_MAX      = "transfer.markup.tier1.max_amount";
    /** Markup fee applied to tier 1 transfers (NGN) */
    public static final String MARKUP_TIER1_FEE      = "transfer.markup.tier1.fee";
    /** Tier 2 transfer upper bound (NGN) — transfers ≤ this value use tier2 fee */
    public static final String MARKUP_TIER2_MAX      = "transfer.markup.tier2.max_amount";
    /** Markup fee applied to tier 2 transfers (NGN) */
    public static final String MARKUP_TIER2_FEE      = "transfer.markup.tier2.fee";
    /** Markup fee applied to tier 3 transfers (NGN — above tier2 max) */
    public static final String MARKUP_TIER3_FEE      = "transfer.markup.tier3.fee";

    // ── NIBSS NIP interbank transfer fee tiers (charged by Rubies at BaaS level) ───────────
    // These go to Rubies / NIBSS automatically — Moniewise does NOT collect them.
    // Defaults match the current NIBSS published schedule (June 2024).
    /** NIP tier 1 upper bound — transfers ≤ ₦5,000 attract the tier 1 NIP fee */
    public static final String NIP_TIER1_MAX         = "transfer.nip.tier1.max_amount";
    /** NIP fee for tier 1 transfers (NGN) — default ₦10.75 */
    public static final String NIP_TIER1_FEE         = "transfer.nip.tier1.fee";
    /** NIP tier 2 upper bound — transfers ≤ ₦50,000 attract the tier 2 NIP fee */
    public static final String NIP_TIER2_MAX         = "transfer.nip.tier2.max_amount";
    /** NIP fee for tier 2 transfers (NGN) — default ₦26.88 */
    public static final String NIP_TIER2_FEE         = "transfer.nip.tier2.fee";
    /** NIP fee for tier 3 transfers (NGN — above tier2 max) — default ₦53.75 */
    public static final String NIP_TIER3_FEE         = "transfer.nip.tier3.fee";

    /** Budget creation fee per 30-day interval (NGN). Set to 0 to disable. */
    public static final String BUDGET_CREATION_FEE   = "budget.creation.fee";

    /** Monthly premium subscription price (NGN) */
    public static final String PREMIUM_MONTHLY_PRICE = "premium.monthly.price";
    /** Comma-separated PremiumFeature values included in premium plan */
    public static final String PREMIUM_FEATURES      = "premium.features";

    // ──────────────────────────────────────────────────────────────────────────

    private final SystemConfigRepository repository;
    private final StringRedisTemplate redis;

    public SystemConfigService(SystemConfigRepository repository, StringRedisTemplate redis) {
        this.repository = repository;
        this.redis      = redis;
    }

    // ── Typed getters ─────────────────────────────────────────────────────────

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        String cached = getFromCache(key);
        if (cached != null) return cached;

        return repository.findByConfigKey(key)
                .map(cfg -> {
                    putInCache(key, cfg.getConfigValue());
                    return cfg.getConfigValue();
                })
                .orElse(defaultValue);
    }

    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        String raw = getString(key);
        if (raw == null) return defaultValue;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("[SystemConfig] Cannot parse '{}' as BigDecimal for key='{}' — using default", raw, key);
            return defaultValue;
        }
    }

    public int getInt(String key, int defaultValue) {
        String raw = getString(key);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("[SystemConfig] Cannot parse '{}' as int for key='{}' — using default", raw, key);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = getString(key);
        if (raw == null) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Upserts a config entry and immediately evicts it from the Redis cache
     * so the new value is visible within one DB round-trip.
     */
    @Transactional
    public void set(String key, String value, String description) {
        SystemConfig config = repository.findByConfigKey(key)
                .orElseGet(() -> {
                    SystemConfig c = new SystemConfig();
                    c.setConfigKey(key);
                    return c;
                });
        config.setConfigValue(value);
        if (description != null) config.setDescription(description);
        repository.save(config);
        evictCache(key);
        logger.info("[SystemConfig] key='{}' updated to '{}'", key, value);
    }

    public void evictCache(String key) {
        try {
            redis.delete(CACHE_PREFIX + key);
        } catch (Exception e) {
            logger.warn("[SystemConfig] Redis evict failed for key='{}': {}", key, e.getMessage());
        }
    }

    // ── Redis helpers ─────────────────────────────────────────────────────────

    private String getFromCache(String key) {
        try {
            return redis.opsForValue().get(CACHE_PREFIX + key);
        } catch (Exception e) {
            logger.warn("[SystemConfig] Redis get failed for key='{}': {}", key, e.getMessage());
            return null;
        }
    }

    private void putInCache(String key, String value) {
        try {
            redis.opsForValue().set(CACHE_PREFIX + key, value, CACHE_TTL);
        } catch (Exception e) {
            logger.warn("[SystemConfig] Redis put failed for key='{}': {}", key, e.getMessage());
        }
    }
}
