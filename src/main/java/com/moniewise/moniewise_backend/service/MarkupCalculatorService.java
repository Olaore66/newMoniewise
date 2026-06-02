package com.moniewise.moniewise_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Calculates the Wisemonie markup fee charged to a user on every external transfer.
 *
 * <h3>Tier logic (all values read from {@code system_config} — never hardcoded)</h3>
 * <pre>
 *   amount ≤ tier1.max_amount   →  tier1.fee   (default ₦50)
 *   amount ≤ tier2.max_amount   →  tier2.fee   (default ₦75)
 *   amount > tier2.max_amount   →  tier3.fee   (default ₦120)
 * </pre>
 *
 * <p>Users on a premium plan with {@code UNLIMITED_TRANSFERS} pay zero markup.
 * The PSP cost (Rubies' own fee) is still borne by Wisemonie in that case.
 *
 * <p>The pre-confirmation screen should call {@link #buildBreakdown} and display
 * the result to the user before they confirm the transfer.
 */
@Service
public class MarkupCalculatorService {

    private static final Logger logger = LoggerFactory.getLogger(MarkupCalculatorService.class);

    private final SystemConfigService systemConfig;
    private final PremiumFeatureService premiumFeatureService;

    public MarkupCalculatorService(SystemConfigService systemConfig,
                                   PremiumFeatureService premiumFeatureService) {
        this.systemConfig           = systemConfig;
        this.premiumFeatureService  = premiumFeatureService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the markup fee for a transfer, respecting premium waivers.
     *
     * @param amount  the transfer amount in NGN
     * @param userId  the ID of the user (null = no premium check)
     * @return markup fee in NGN, or ZERO if user has UNLIMITED_TRANSFERS
     */
    public BigDecimal calculateMarkup(BigDecimal amount, Long userId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        if (userId != null && premiumFeatureService.hasFeature(userId, "UNLIMITED_TRANSFERS")) {
            logger.debug("[Markup] User {} has UNLIMITED_TRANSFERS — markup waived", userId);
            return BigDecimal.ZERO;
        }

        return tierFee(amount);
    }

    /**
     * Calculates markup without user context (e.g. pre-authentication preview).
     */
    public BigDecimal calculateMarkup(BigDecimal amount) {
        return calculateMarkup(amount, null);
    }

    /**
     * Builds the full fee breakdown for the pre-confirmation screen.
     *
     * <p>Includes the NIBSS NIP bank charge (automatically deducted by Rubies)
     * AND the Moniewise markup fee so the user sees the full cost up-front.
     *
     * <p>Frontend should display: {@code breakdown.displayText()}
     * e.g. "Send ₦5,000.00 · Bank fee ₦10.75 · Moniewise fee ₦50.00 · Total ₦5,060.75"
     *
     * <p><strong>Revenue rule:</strong> only {@code markupFee} enters the Moniewise
     * revenue wallet. {@code bankCharge} goes to Rubies/NIBSS automatically — we
     * never collect it.
     */
    public FeeBreakdown buildBreakdown(BigDecimal transferAmount, Long userId) {
        BigDecimal markup    = calculateMarkup(transferAmount, userId);
        BigDecimal nipFee    = calculateNipFee(transferAmount);
        BigDecimal total     = transferAmount.add(nipFee).add(markup);
        return new FeeBreakdown(transferAmount, nipFee, markup, total);
    }

    /**
     * Calculates the NIBSS NIP interbank fee for this transfer amount.
     *
     * <p>This fee is charged by Rubies at the BaaS level when a transfer is
     * initiated — it is NOT Moniewise revenue. Defaults follow the NIBSS
     * published schedule; all thresholds and amounts are configurable via
     * {@code system_config}.
     *
     * <ul>
     *   <li>≤ ₦5,000   → ₦10.75</li>
     *   <li>≤ ₦50,000  → ₦26.88</li>
     *   <li>> ₦50,000  → ₦53.75</li>
     * </ul>
     */
    public BigDecimal calculateNipFee(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        BigDecimal tier1Max = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER1_MAX, new BigDecimal("5000"));
        BigDecimal tier2Max = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER2_MAX, new BigDecimal("50000"));
        BigDecimal tier1Fee = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER1_FEE, new BigDecimal("10.75"));
        BigDecimal tier2Fee = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER2_FEE, new BigDecimal("26.88"));
        BigDecimal tier3Fee = systemConfig.getBigDecimal(SystemConfigService.NIP_TIER3_FEE, new BigDecimal("53.75"));

        if (amount.compareTo(tier1Max) <= 0) return tier1Fee;
        if (amount.compareTo(tier2Max) <= 0) return tier2Fee;
        return tier3Fee;
    }

    // ── Tier resolution ───────────────────────────────────────────────────────

    private BigDecimal tierFee(BigDecimal amount) {
        BigDecimal tier1Max = systemConfig.getBigDecimal(SystemConfigService.MARKUP_TIER1_MAX, new BigDecimal("5000"));
        BigDecimal tier2Max = systemConfig.getBigDecimal(SystemConfigService.MARKUP_TIER2_MAX, new BigDecimal("50000"));
        BigDecimal tier1Fee = systemConfig.getBigDecimal(SystemConfigService.MARKUP_TIER1_FEE, new BigDecimal("50"));
        BigDecimal tier2Fee = systemConfig.getBigDecimal(SystemConfigService.MARKUP_TIER2_FEE, new BigDecimal("75"));
        BigDecimal tier3Fee = systemConfig.getBigDecimal(SystemConfigService.MARKUP_TIER3_FEE, new BigDecimal("120"));

        if (amount.compareTo(tier1Max) <= 0) return tier1Fee;
        if (amount.compareTo(tier2Max) <= 0) return tier2Fee;
        return tier3Fee;
    }

    // ── Value object ──────────────────────────────────────────────────────────

    /**
     * Immutable fee breakdown returned to controllers and sent to the frontend
     * before the user confirms a transfer.
     *
     * <ul>
     *   <li>{@code bankCharge}  — NIBSS NIP fee charged by Rubies. Goes to the bank.
     *       Moniewise does NOT collect this.</li>
     *   <li>{@code markupFee}   — Moniewise revenue fee. Transferred to the Moniewise
     *       Rubies revenue wallet after the transfer succeeds.</li>
     *   <li>{@code totalFromEnvelope} — {@code transferAmount + bankCharge + markupFee}</li>
     * </ul>
     */
    public record FeeBreakdown(
            BigDecimal transferAmount,
            BigDecimal bankCharge,
            BigDecimal markupFee,
            BigDecimal totalFromEnvelope
    ) {
        /** Human-readable summary for the pre-confirmation screen. */
        public String displayText() {
            return String.format(
                    "Send ₦%,.2f · Bank fee ₦%,.2f · Moniewise fee ₦%,.2f · Total ₦%,.2f",
                    transferAmount, bankCharge, markupFee, totalFromEnvelope);
        }
    }
}
