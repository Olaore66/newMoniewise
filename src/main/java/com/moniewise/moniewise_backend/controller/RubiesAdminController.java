package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.psp.rubies.RubiesGateway;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Admin-only endpoints for Rubies MFB operations that cannot be done via the Rubies dashboard.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 *
 * <h3>One-time setup sequence</h3>
 * <ol>
 *   <li>{@code GET  /admin/rubies/revenue-wallet}       — check if already configured</li>
 *   <li>{@code POST /admin/rubies/setup-revenue-wallet} — create the wallet on Rubies</li>
 *   <li>Done — all subsequent markup fees route to this wallet automatically</li>
 * </ol>
 */
@RestController
@RequestMapping("/admin/rubies")
@PreAuthorize("hasRole('ADMIN')")
public class RubiesAdminController {

    private static final Logger logger = LoggerFactory.getLogger(RubiesAdminController.class);

    private final RubiesGateway rubiesGateway;
    private final SystemConfigService systemConfig;
    private final WalletRepository walletRepository;

    public RubiesAdminController(RubiesGateway rubiesGateway,
                                 SystemConfigService systemConfig,
                                 WalletRepository walletRepository) {
        this.rubiesGateway    = rubiesGateway;
        this.systemConfig     = systemConfig;
        this.walletRepository = walletRepository;
    }

    /**
     * GET /admin/rubies/revenue-wallet
     *
     * <p>Returns the currently configured Moniewise Rubies revenue wallet, or a
     * NOT_CONFIGURED status if {@code setup-revenue-wallet} has not been called yet.
     *
     * <p>Postman: no body needed.
     */
    @GetMapping("/revenue-wallet")
    public ResponseEntity<?> getRevenueWallet() {
        String accountNumber = systemConfig.getString(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
        String accountName   = systemConfig.getString(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME);

        if (accountNumber == null || accountNumber.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "status",  false,
                    "message", "Rubies revenue wallet not yet configured. " +
                               "Call POST /admin/rubies/setup-revenue-wallet to create it."
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status",        true,
                "accountNumber", accountNumber,
                "accountName",   accountName != null ? accountName : "",
                "bank",          "Rubies MFB",
                "bankCode",      "090175",
                "message",       "Revenue wallet is active. All markup fees are routed here."
        ));
    }

    /**
     * POST /admin/rubies/setup-revenue-wallet
     *
     * <p>Calls the Rubies BaaS API to create Moniewise's own revenue wallet, then
     * saves the returned account number into {@code system_config} so the fee-routing
     * logic picks it up immediately (no restart needed).
     *
     * <p>This is idempotent — if a wallet is already configured it returns the existing
     * details without creating a new one.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "bvn":         "12345678901",       ← company director / authorised rep BVN
     *   "firstName":   "Moniewise",
     *   "lastName":    "Technologies",
     *   "email":       "revenue@moniewise.com",
     *   "phone":       "08012345678",
     *   "dateOfBirth": "1990-01-01",         ← YYYY-MM-DD, must match BVN record
     *   "displayName": "MONIEWISE TECHNOLOGIES"  ← optional, shown in transfer narrations
     * }
     * </pre>
     *
     * <p>Response (success):
     * <pre>
     * {
     *   "status":        true,
     *   "accountNumber": "7012345678",
     *   "accountName":   "MONIEWISE TECHNOLOGIES",
     *   "bank":          "Rubies MFB",
     *   "message":       "Revenue wallet created and configured successfully."
     * }
     * </pre>
     */
    @PostMapping("/setup-revenue-wallet")
    public synchronized ResponseEntity<?> setupRevenueWallet(@RequestBody Map<String, String> body) {

        // ── Guard: already configured? (re-check inside synchronized to prevent TOCTOU race) ──
        // Without synchronized, two concurrent admin requests could both pass the null check,
        // both call createMerchantWallet, and create two orphaned wallets on Rubies.
        String existing = systemConfig.getString(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER);
        if (existing != null && !existing.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "status",        true,
                    "accountNumber", existing,
                    "accountName",   systemConfig.getString(
                            SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME, ""),
                    "bank",          "Rubies MFB",
                    "message",       "Revenue wallet already configured — no new wallet created."
            ));
        }

        // ── Validate required fields ──────────────────────────────────────────
        String bvn         = body.get("bvn");
        String firstName   = body.get("firstName");
        String lastName    = body.get("lastName");
        String email       = body.get("email");
        String phone       = body.get("phone");
        String dob         = body.get("dateOfBirth");
        String displayName = body.getOrDefault("displayName", "MONIEWISE TECHNOLOGIES");

        if (isBlank(bvn) || isBlank(firstName) || isBlank(lastName)
                || isBlank(email) || isBlank(phone) || isBlank(dob)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", false,
                    "error",  "bvn, firstName, lastName, email, phone, and dateOfBirth are all required."
            ));
        }

        // ── Call Rubies API ───────────────────────────────────────────────────
        try {
            Map<String, String> result = rubiesGateway.createMerchantWallet(
                    bvn, firstName, lastName, email, phone, dob);

            String accountNumber = result.get("accountNumber");
            String accountName   = result.getOrDefault("accountName", displayName);

            // Guard: Rubies returned success but no account number — do NOT save blank
            // to system_config or the idempotency guard will think it's already configured
            // and block every future attempt.
            if (isBlank(accountNumber)) {
                logger.error("[Admin] Rubies createMerchantWallet returned success but accountNumber is blank — not saving");
                return ResponseEntity.internalServerError().body(Map.of(
                        "status", false,
                        "error",  "Rubies returned a success response but did not include an account number. " +
                                  "Check the Rubies API response format and try again."
                ));
            }

            // Persist to system_config — takes effect immediately (Redis cache evicted by set())
            systemConfig.set(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER,
                    accountNumber,
                    "Moniewise Rubies revenue wallet — auto-created via admin API"
            );
            String savedName = accountName != null ? accountName : displayName;
            systemConfig.set(
                    SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME,
                    savedName,
                    "Display name for Moniewise Rubies revenue wallet"
            );

            // Also link the DB revenue wallet record so it appears in wallet queries.
            linkRevenueWalletRecord(accountNumber, savedName);

            logger.info("[Admin] Rubies revenue wallet created and saved: acct={} name={}",
                    accountNumber, accountName);

            return ResponseEntity.ok(Map.of(
                    "status",        true,
                    "accountNumber", accountNumber,
                    "accountName",   accountName != null ? accountName : displayName,
                    "customerId",    result.getOrDefault("customerId", ""),
                    "bank",          "Rubies MFB",
                    "bankCode",      "090175",
                    "message",       "Revenue wallet created and configured successfully. " +
                                     "All future markup fees will route here automatically."
            ));

        } catch (RuntimeException e) {
            logger.error("[Admin] Failed to create Rubies revenue wallet: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", false,
                    "error",  e.getMessage()
            ));
        }
    }

    /**
     * POST /admin/rubies/register-revenue-wallet
     *
     * <p>Links Moniewise's <em>existing</em> Rubies MFB business account (the one
     * created when you signed up on the Rubies dashboard) to this server's DB and
     * system_config — <strong>no new wallet is created on Rubies</strong>.
     *
     * <p>Use this instead of {@code setup-revenue-wallet} when you already have a
     * Rubies account and just need to tell the backend where to route the markup fee.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "accountNumber": "7012345678",              ← your existing Rubies account number
     *   "accountName":   "MONIEWISE TECHNOLOGIES"   ← optional display name
     * }
     * </pre>
     *
     * <p>Effect:
     * <ol>
     *   <li>Saves the account number to {@code system_config} — the fee-routing logic
     *       picks this up within 5 seconds (Redis TTL).</li>
     *   <li>Sets {@code providerWalletRef} on the internal revenue-wallet DB record
     *       so the wallet is visible in admin queries and reconciliation runs.</li>
     * </ol>
     *
     * <p>This endpoint is idempotent — calling it again with the same number is safe.
     */
    @PostMapping("/register-revenue-wallet")
    @Transactional
    public synchronized ResponseEntity<?> registerRevenueWallet(@RequestBody Map<String, String> body) {

        String accountNumber = body.get("accountNumber");
        String accountName   = body.getOrDefault("accountName", "MONIEWISE TECHNOLOGIES");

        if (isBlank(accountNumber)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", false,
                    "error",  "accountNumber is required."
            ));
        }

        // Persist to system_config (evicts Redis cache immediately)
        systemConfig.set(
                SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER,
                accountNumber,
                "Moniewise Rubies revenue wallet — registered via admin API (existing account)"
        );
        systemConfig.set(
                SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME,
                accountName,
                "Display name for Moniewise Rubies revenue wallet"
        );

        // Link the DB revenue wallet record
        boolean dbLinked = linkRevenueWalletRecord(accountNumber, accountName);

        logger.info("[Admin] Existing Rubies revenue wallet registered: acct={} name={} dbLinked={}",
                accountNumber, accountName, dbLinked);

        return ResponseEntity.ok(Map.of(
                "status",        true,
                "accountNumber", accountNumber,
                "accountName",   accountName,
                "bank",          "Rubies MFB",
                "bankCode",      "090175",
                "dbLinked",      dbLinked,
                "message",       "Revenue wallet registered successfully. " +
                                 "All future markup fees will route here. " +
                                 (dbLinked
                                  ? "DB revenue wallet record updated."
                                  : "Note: no DB revenue wallet record found — system_config updated only.")
        ));
    }

    /**
     * DELETE /admin/rubies/revenue-wallet
     *
     * <p>Clears the configured revenue wallet from system_config. Use this if you need
     * to re-create the wallet with different details. Markup fees will fall back to
     * internal DB tracking only until a new wallet is configured.
     */
    @DeleteMapping("/revenue-wallet")
    public ResponseEntity<?> clearRevenueWallet(Authentication authentication) {
        String adminEmail = authentication != null ? authentication.getName() : "unknown";
        String auditNote  = "Cleared by admin: " + adminEmail;

        systemConfig.set(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NUMBER, "", auditNote);
        systemConfig.set(SystemConfigService.RUBIES_REVENUE_ACCOUNT_NAME,   "", auditNote);

        // Warn loudly — this stops all live fee routing until re-configured
        logger.warn("[Admin] ⚠️  Rubies revenue wallet configuration CLEARED by admin={}. " +
                "Markup fees will only be tracked internally until a new wallet is set up.", adminEmail);

        return ResponseEntity.ok(Map.of(
                "status",  true,
                "message", "Revenue wallet cleared. Fees will track internally until re-configured."
        ));
    }

    /**
     * Finds the internal revenue-wallet DB record (flagged {@code is_revenue_wallet = true})
     * and sets its {@code providerWalletRef} and {@code accountNumber} to the given
     * Rubies account number so it appears in admin wallet queries and reconciliation.
     *
     * <p>This does NOT create a new wallet — it only updates the existing revenue-wallet
     * record that was seeded at platform setup time.
     *
     * @return {@code true} if the record was found and updated; {@code false} if no
     *         revenue-wallet record exists yet (system_config still gets updated).
     */
    private boolean linkRevenueWalletRecord(String accountNumber, String accountName) {
        return walletRepository.findByRevenueWalletTrue()
                .map(revenueWallet -> {
                    revenueWallet.setProviderWalletRef(accountNumber);
                    revenueWallet.setAccountNumber(accountNumber);
                    revenueWallet.setProviderName(RubiesGateway.PROVIDER_NAME);
                    revenueWallet.setBankName("Rubies MFB");
                    revenueWallet.setUpdatedAt(LocalDateTime.now());
                    walletRepository.save(revenueWallet);
                    logger.info("[Admin] Revenue wallet DB record linked: walletId={} acct={}",
                            revenueWallet.getId(), accountNumber);
                    return true;
                })
                .orElseGet(() -> {
                    logger.warn("[Admin] findByRevenueWalletTrue() returned empty — " +
                            "system_config updated but DB wallet record not linked. " +
                            "Ensure a wallet row exists with is_revenue_wallet=true.");
                    return false;
                });
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
