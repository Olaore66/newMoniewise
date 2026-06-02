package com.moniewise.moniewise_backend.psp.rubies;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.rubies.dto.*;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rubies MFB BaaS integration — completely decoupled from Providus and SecureWave.
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code RUBIES_API_KEY} env var — API key in Authorization header</li>
 *   <li>{@code RUBIES_BASE_URL} env var — base URL (default: https://api-sme-dev.rubies.ng)</li>
 *   <li>{@code system_config.rubies.stage} — "dev" or "prod" path parameter</li>
 * </ul>
 *
 * <h3>Switching to Rubies</h3>
 * Set {@code system_config.psp.active = RUBIES}. No code change required.
 *
 * <h3>Response codes</h3>
 * "00" = success, "09"/"90"/"99" = pending (poll TSQ), else = failed.
 */
@Component
public class RubiesGateway implements PaymentGateway {

    public static final String PROVIDER_NAME = "RUBIES";

    private static final Logger logger = LoggerFactory.getLogger(RubiesGateway.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SystemConfigService systemConfig;

    @Value("${rubies.api.key:}")
    private String apiKey;

    @Value("${rubies.base.url:https://api-sme-dev.rubies.ng}")
    private String baseUrl;

    /**
     * HMAC-SHA512 secret Rubies uses to sign webhook notifications.
     * Set {@code RUBIES_WEBHOOK_SECRET} env var (maps to {@code rubies.webhook.secret}).
     * While blank the gateway logs a security warning and accepts all webhooks —
     * tighten this before going live.
     */
    @Value("${rubies.webhook.secret:}")
    private String webhookSecret;

    public RubiesGateway(RestTemplate restTemplate,
                         ObjectMapper objectMapper,
                         SystemConfigService systemConfig) {
        this.restTemplate = restTemplate;
        this.objectMapper  = objectMapper;
        this.systemConfig  = systemConfig;
    }

    // ── Startup validation ────────────────────────────────────────────────────

    @PostConstruct
    public void validateConfiguration() {
        if (apiKey == null || apiKey.isBlank()) {
            logger.error("[Rubies] RUBIES_API_KEY (rubies.api.key) is not set — " +
                    "all Rubies API calls will fail with 401. Set the env var before starting.");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            logger.warn("[Rubies][SECURITY] RUBIES_WEBHOOK_SECRET (rubies.webhook.secret) is not set — " +
                    "webhook signature verification is DISABLED. Set the env var before going live.");
        }
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    // ── Wallet creation ───────────────────────────────────────────────────────

    /**
     * Creates a Rubies BaaS wallet for the user.
     *
     * <p>Called at the end of profile completion, after BVN has been verified
     * via SecureWave. The BVN name fields in {@code profileData} must match
     * the Rubies name-similarity threshold (~90%).
     *
     * @return map with keys: providerCustomerRef, providerWalletRef, accountNumber, bank
     */
    @Override
    public Map<String, String> createVirtualAccount(User user) {
        String stage = stage();

        // Extract profile fields from JSONB profileData map
        Map<String, Object> profile = user.getProfileData() != null
                ? user.getProfileData() : Collections.emptyMap();

        String firstName = str(profile, "bvnFirstName", str(profile, "firstName", ""));
        String lastName  = str(profile, "bvnLastName",  str(profile, "lastName",  ""));
        // dob must be in YYYY-MM-DD format — Rubies validates this against the BVN record
        String dob       = str(profile, "dateOfBirth", str(profile, "dob", ""));

        RubiesCreateWalletRequest req = new RubiesCreateWalletRequest(
                user.getBvn(),
                firstName,
                lastName,
                user.getEmail(),
                user.getPhone(),
                dob
        );

        String url = baseUrl + "/" + stage + "/baas-wallet/create-wallet";
        logger.info("[Rubies] Creating wallet for user={} at {}", user.getId(), url);

        try {
            ResponseEntity<RubiesCreateWalletResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesCreateWalletResponse.class);

            RubiesCreateWalletResponse body = response.getBody();

            if (body == null || !body.isSuccess()) {
                String msg = body != null ? body.getResponseMessage() : "null response";
                logger.error("[Rubies] createVirtualAccount failed for user={}: {}", user.getId(), msg);
                throw new RuntimeException("Rubies wallet creation failed: " + msg);
            }

            // Rubies response is FLAT — no nested data wrapper
            logger.info("[Rubies] Wallet created for user={} → account={}", user.getId(), body.getAccountNumber());

            Map<String, String> result = new HashMap<>();
            result.put("providerCustomerRef", body.getCustomerId());
            result.put("providerWalletRef",   body.getAccountNumber());
            result.put("accountNumber",        body.getAccountNumber());
            result.put("accountName",          body.getAccountName());
            // Bank details are always fixed for Rubies wallets
            result.put("bank",                 "Rubies MFB");
            result.put("bankCode",             "090175");
            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] createVirtualAccount exception for user={}: {}", user.getId(), e.getMessage(), e);
            throw new RuntimeException("Rubies wallet creation error: " + e.getMessage(), e);
        }
    }

    // ── Merchant / revenue wallet creation ───────────────────────────────────

    /**
     * Creates a Rubies BaaS wallet using raw fields instead of a {@link User} entity.
     *
     * <p>Used by the admin endpoint to create Moniewise's own Rubies revenue wallet.
     * The BVN and personal details must belong to an authorised company representative
     * (director, founder) — Rubies validates the BVN against NIBSS records.
     *
     * @return map with keys: accountNumber, accountName, customerId
     */
    public Map<String, String> createMerchantWallet(
            String bvn,
            String firstName,
            String lastName,
            String email,
            String phone,
            String dateOfBirth) {

        String stage = stage();
        String url   = baseUrl + "/" + stage + "/baas-wallet/create-wallet";

        RubiesCreateWalletRequest req = new RubiesCreateWalletRequest(
                bvn, firstName, lastName, email, phone, dateOfBirth);

        logger.info("[Rubies] Creating merchant/revenue wallet: email={} at {}", email, url);

        try {
            ResponseEntity<RubiesCreateWalletResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesCreateWalletResponse.class);

            RubiesCreateWalletResponse body = response.getBody();

            if (body == null || !body.isSuccess()) {
                String msg = body != null ? body.getResponseMessage() : "null response";
                logger.error("[Rubies] createMerchantWallet failed: {}", msg);
                throw new RuntimeException("Rubies merchant wallet creation failed: " + msg);
            }

            logger.info("[Rubies] Merchant wallet created: accountNumber={} name={}",
                    body.getAccountNumber(), body.getAccountName());

            Map<String, String> result = new HashMap<>();
            result.put("accountNumber", body.getAccountNumber());
            result.put("accountName",   body.getAccountName());
            result.put("customerId",    body.getCustomerId());
            result.put("bank",          "Rubies MFB");
            result.put("bankCode",      "090175");
            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] createMerchantWallet exception: {}", e.getMessage(), e);
            throw new RuntimeException("Rubies merchant wallet creation error: " + e.getMessage(), e);
        }
    }

    // ── Name enquiry (account resolve) ───────────────────────────────────────

    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        String url = baseUrl + "/" + stage() + "/baas-transaction/name-enquiry";
        RubiesNameEnquiryRequest req = new RubiesNameEnquiryRequest(bankCode, accountNumber);

        logger.debug("[Rubies] Name enquiry: bank={} acct={}", bankCode, accountNumber);

        try {
            ResponseEntity<RubiesNameEnquiryResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesNameEnquiryResponse.class);

            RubiesNameEnquiryResponse body = response.getBody();

            if (body == null || !body.isSuccess() || body.getData() == null) {
                String msg = body != null ? body.getResponseMessage() : "null response";
                throw new RuntimeException("Rubies name enquiry failed: " + msg);
            }

            return body.getData().getAccountName();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] resolveAccount exception: {}", e.getMessage(), e);
            throw new RuntimeException("Rubies name enquiry error: " + e.getMessage(), e);
        }
    }

    // ── Bank list ─────────────────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> getSupportedBanks() {
        String url = baseUrl + "/" + stage() + "/baas-transaction/bank-list";
        RubiesBankListRequest req = new RubiesBankListRequest();

        logger.debug("[Rubies] Fetching bank list");

        try {
            ResponseEntity<RubiesBankListResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesBankListResponse.class);

            RubiesBankListResponse body = response.getBody();

            if (body == null || !body.isSuccess() || body.getData() == null) {
                logger.warn("[Rubies] getSupportedBanks returned non-success: {}",
                        body != null ? body.getResponseMessage() : "null");
                return Collections.emptyList();
            }

            return body.getData().stream()
                    .map(entry -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("bankName", entry.getBankName());
                        m.put("bankCode", entry.getBankCode());
                        return m;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("[Rubies] getSupportedBanks exception: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ── Fund transfer (with full context) ────────────────────────────────────

    /**
     * Sends funds via Rubies NIP transfer.
     *
     * <p>This is the primary method — always prefer this over the fallback
     * {@link #initiateTransfer} when the debit account details are available.
     *
     * <p><strong>amount is passed as STRING per Rubies spec.</strong>
     */
    @Override
    public String initiateTransferWithContext(
            String debitAccountNumber, String debitAccountName,
            String creditBankCode, String creditBankName,
            String creditAccountNumber, String creditAccountName,
            BigDecimal amount, String reference, String narration) {

        String url = baseUrl + "/" + stage() + "/baas-transaction/fund-transfer";

        // Rubies requires amount as a plain numeric string
        String amountStr = amount.toPlainString();

        RubiesFundTransferRequest req = new RubiesFundTransferRequest(
                debitAccountNumber, debitAccountName,
                creditBankCode, creditBankName,
                creditAccountNumber, creditAccountName,
                amountStr, reference, narration
        );

        logger.info("[Rubies] Fund transfer: ref={} amount={} from={} to={}/{}",
                reference, amountStr, debitAccountNumber, creditBankCode, creditAccountNumber);

        try {
            ResponseEntity<RubiesFundTransferResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesFundTransferResponse.class);

            RubiesFundTransferResponse body = response.getBody();

            if (body == null) {
                throw new RuntimeException("Rubies fund transfer returned null response for ref=" + reference);
            }

            if (body.isFailed()) {
                logger.error("[Rubies] Fund transfer FAILED: ref={} code={} msg={}",
                        reference, body.getResponseCode(), body.getResponseMessage());
                throw new RuntimeException("Rubies transfer failed [" + body.getResponseCode() + "]: "
                        + body.getResponseMessage());
            }

            // "00" = success, "09"/"90"/"99" = pending — both are acceptable outcomes here.
            // The caller/webhook handler will finalize on pending.
            String sessionId = body.getData() != null ? body.getData().getSessionId() : reference;
            logger.info("[Rubies] Fund transfer {}: ref={} sessionId={}",
                    body.isSuccess() ? "SUCCESS" : "PENDING", reference, sessionId);

            return sessionId != null ? sessionId : reference;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] Fund transfer exception: ref={} error={}", reference, e.getMessage(), e);
            throw new RuntimeException("Rubies transfer error: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback — called by {@link PaymentGateway#initiateTransferWithContext} default
     * and by any code that calls the old interface directly.
     *
     * <p>Without a {@code debitAccountNumber} we cannot build a valid Rubies request.
     * This should never be invoked in normal flow — log a warning and throw so the
     * caller is forced to use {@link #initiateTransferWithContext}.
     */
    @Override
    public String initiateTransfer(String bankCode, String accountNumber, String accountName,
                                   BigDecimal amount, String uniqueReference, String narration) {
        logger.error("[Rubies] initiateTransfer called without debitAccountNumber — " +
                "use initiateTransferWithContext instead. ref={}", uniqueReference);
        throw new UnsupportedOperationException(
                "Rubies requires debitAccountNumber. Use initiateTransferWithContext.");
    }

    // ── Balance enquiry ───────────────────────────────────────────────────────

    @Override
    public Optional<BigDecimal> fetchWalletBalance(String walletReference) {
        String url = baseUrl + "/" + stage() + "/baas-wallet/balance-enquiry";
        RubiesBalanceEnquiryRequest req = new RubiesBalanceEnquiryRequest(walletReference);

        logger.debug("[Rubies] Balance enquiry: acct={}", walletReference);

        try {
            ResponseEntity<RubiesBalanceEnquiryResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesBalanceEnquiryResponse.class);

            RubiesBalanceEnquiryResponse body = response.getBody();

            if (body == null || !body.isSuccess() || body.getData() == null) {
                return Optional.empty();
            }

            return Optional.of(body.getData().availableBalanceDecimal());

        } catch (Exception e) {
            logger.warn("[Rubies] fetchWalletBalance exception for acct={}: {}", walletReference, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Transaction status query ──────────────────────────────────────────────

    @Override
    public Optional<String> fetchTransactionStatus(String providerReference) {
        String url = baseUrl + "/" + stage() + "/baas-transaction/tsq";
        RubiesTsqRequest req = new RubiesTsqRequest(providerReference);

        logger.debug("[Rubies] TSQ: ref={}", providerReference);

        try {
            ResponseEntity<RubiesTsqResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesTsqResponse.class);

            RubiesTsqResponse body = response.getBody();

            if (body == null) return Optional.empty();

            String status;
            if (body.isSuccess())  status = "SUCCESS";
            else if (body.isPending()) status = "PENDING";
            else status = "FAILED";

            logger.info("[Rubies] TSQ result: ref={} → {}", providerReference, status);
            return Optional.of(status);

        } catch (Exception e) {
            logger.warn("[Rubies] fetchTransactionStatus exception for ref={}: {}", providerReference, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Webhook helpers ───────────────────────────────────────────────────────

    /**
     * Rubies webhooks have NO {@code event} field — the type must be synthesised
     * from {@code drCr} and {@code responseCode}:
     *
     * <ul>
     *   <li>DR + 00  → TRANSFER.SUCCESS  (outbound transfer confirmed)</li>
     *   <li>DR + !00 → TRANSFER.FAILED   (outbound transfer rejected)</li>
     *   <li>CR + 00  → CREDIT.RECEIVED   (inbound deposit)</li>
     * </ul>
     */
    @Override
    public String extractWebhookEventType(String rawPayload) {
        try {
            RubiesWebhookPayload payload = objectMapper.readValue(rawPayload, RubiesWebhookPayload.class);
            String drCr         = payload.getDrCr()         != null ? payload.getDrCr().toUpperCase()         : "";
            String responseCode = payload.getResponseCode() != null ? payload.getResponseCode()               : "";

            if ("DR".equals(drCr)) {
                return "00".equals(responseCode) ? "TRANSFER.SUCCESS" : "TRANSFER.FAILED";
            }
            if ("CR".equals(drCr) && "00".equals(responseCode)) {
                return "CREDIT.RECEIVED";
            }
            logger.warn("[Rubies] Unknown webhook drCr={} responseCode={} — treating as UNKNOWN", drCr, responseCode);
            return "UNKNOWN";
        } catch (JsonProcessingException e) {
            logger.warn("[Rubies] Cannot parse webhook event type: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * Extracts our payment reference from the Rubies webhook.
     * Rubies echoes back the {@code transactionReference} we sent as {@code paymentReference}.
     * There is NO nested {@code data} object.
     */
    @Override
    public String extractWebhookReference(String rawPayload) {
        try {
            RubiesWebhookPayload payload = objectMapper.readValue(rawPayload, RubiesWebhookPayload.class);
            return payload.getPaymentReference();
        } catch (JsonProcessingException e) {
            logger.warn("[Rubies] Cannot parse webhook reference: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validates the Rubies webhook {@code X-Rubies-Signature} header using HMAC-SHA512.
     *
     * <p>Operates in two modes:
     * <ul>
     *   <li><b>Secret set</b> ({@code rubies.webhook.secret} / {@code RUBIES_WEBHOOK_SECRET}):
     *       computes HMAC-SHA512 of the raw payload and compares against the header value.
     *       Returns {@code false} (rejects) if the header is absent or the digest does not match.</li>
     *   <li><b>No secret</b>: logs a security warning and accepts the request so webhooks
     *       are not silently dropped during development. Tighten before production.</li>
     * </ul>
     */
    @Override
    public boolean validateWebhookSignature(String signature, String rawPayload) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            logger.warn("[Rubies][SECURITY] No webhook secret configured — accepting without verification. " +
                    "Set RUBIES_WEBHOOK_SECRET (rubies.webhook.secret) before production.");
            return true;
        }
        if (signature == null || signature.isBlank()) {
            logger.warn("[Rubies-WEBHOOK] Missing X-Rubies-Signature header — request rejected.");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hashBytes = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            String expected = hex.toString();
            boolean valid = expected.equalsIgnoreCase(signature.trim());
            if (!valid) {
                logger.warn("[Rubies-WEBHOOK] Signature mismatch — expected={} received={}",
                        expected, signature.trim());
            }
            return valid;
        } catch (Exception e) {
            logger.error("[Rubies-WEBHOOK] HMAC-SHA512 validation threw an exception", e);
            return false;
        }
    }

    // ── PSP methods not applicable to Rubies (BaaS model) ────────────────────

    /**
     * For Rubies the recipient bank details are stored locally on the Wallet entity,
     * not registered at the PSP level — so we simply return true to let WalletService
     * persist the details without making a Rubies API call.
     */
    @Override
    public boolean updateWithdrawalBankInfo(String email, String bankName, String accountName,
                                            String bankCode, String accountNumber) {
        logger.debug("[Rubies] updateWithdrawalBankInfo: storing locally (no PSP call needed)");
        return true;
    }

    @Override
    public Map<String, Object> getWithdrawalBankInfo(String email) {
        return Collections.emptyMap();
    }

    @Override
    public String initiateWithdrawal(String email, BigDecimal amount, String narration) {
        throw new UnsupportedOperationException(
                "Rubies uses NIP fund-transfer for withdrawals. Use initiateTransferWithContext.");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String stage() {
        return systemConfig.getString(SystemConfigService.RUBIES_STAGE, "dev");
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", apiKey);
        return headers;
    }

    private String str(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        String s = val.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }
}
