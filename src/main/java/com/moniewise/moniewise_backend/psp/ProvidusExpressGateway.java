package com.moniewise.moniewise_backend.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@Component
public class ProvidusExpressGateway implements PaymentGateway {

    public static final String PROVIDER_NAME = "PROVIDUS";

    private static final Logger logger = LoggerFactory.getLogger(ProvidusExpressGateway.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${providus.base-url:https://payment.xpress-wallet.com}")
    private String baseUrl;

    @Value("${providus.merchant-api-key:}")
    private String merchantApiKey;

    @Value("${providus.account-prefix:}")
    private String accountPrefix;

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    // ─────────────────────────────────────────────────────────────
    // Create Customer Wallet
    // POST /api/v1/wallet
    // ─────────────────────────────────────────────────────────────
    @Override
    public Map<String, String> createVirtualAccount(User user) {
        String url = baseUrl + "/api/v1/wallet";

        Map<String, Object> profile = user.getProfileData() != null
                ? user.getProfileData()
                : Collections.emptyMap();

        String firstName  = safeString(profile.get("firstName"), "Wisemonie");
        String lastName   = safeString(profile.get("lastName"), "User");
        String dob        = safeString(profile.get("dateOfBirth"), "");
        String address    = safeString(profile.get("address"), "");
        String phone      = normalizePhone(user.getPhone());
        String bvn        = user.getBvn() != null ? user.getBvn() : "";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bvn",           bvn);
        body.put("firstName",     firstName);
        body.put("lastName",      lastName);
        body.put("accountPrefix", accountPrefix);
        body.put("dateOfBirth",   dob);
        body.put("phoneNumber",   phone);
        body.put("email",         user.getEmail());
        body.put("address",       address);
        body.put("metadata",      Map.of("platform", "moniewise"));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, buildHeaders());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());

            if (!root.path("status").asBoolean(false)) {
                String msg = root.path("message").asText("Providus wallet creation failed");
                logger.error("Providus rejected wallet creation for {}: {}", user.getEmail(), msg);
                throw new RuntimeException("Providus: " + msg);
            }

            JsonNode wallet   = root.path("wallet");
            JsonNode customer = root.path("customer");

            Map<String, String> result = new HashMap<>();
            result.put("accountNumber",      wallet.path("accountNumber").asText());
            result.put("bank",               wallet.path("bankName").asText("Xpress Wallet"));
            result.put("accountName",        wallet.path("accountName").asText());
            result.put("providerCustomerRef", customer.path("id").asText());
            result.put("providerWalletRef",  wallet.path("id").asText());
            result.put("masterWalletRef",    wallet.path("accountReference").asText());

            logger.info("Providus wallet created for {} — account: {}",
                    user.getEmail(), result.get("accountNumber"));
            return result;

        } catch (HttpStatusCodeException e) {
            logger.error("Providus HTTP {} for {}: {}",
                    e.getStatusCode(), user.getEmail(), e.getResponseBodyAsString());
            throw new RuntimeException("Providus wallet creation failed: " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Providus wallet creation error for {}: {}", user.getEmail(), e.getMessage());
            throw new RuntimeException("Providus wallet creation failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Remaining PaymentGateway methods
    // These will be implemented when Providus docs for each
    // operation are available. They throw clearly so callers
    // know what is missing rather than silently failing.
    // ─────────────────────────────────────────────────────────────

    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        throw new UnsupportedOperationException("Providus account resolution not yet implemented");
    }

    @Override
    public List<Map<String, Object>> getSupportedBanks() {
        throw new UnsupportedOperationException("Providus bank list not yet implemented");
    }

    @Override
    public String initiateTransfer(String bankCode, String accountNumber, String accountName,
                                   BigDecimal amount, String uniqueReference, String narration) {
        throw new UnsupportedOperationException("Providus transfer not yet implemented");
    }

    @Override
    public boolean updateWithdrawalBankInfo(String email, String bankName, String accountName,
                                            String bankCode, String accountNumber) {
        throw new UnsupportedOperationException("Providus withdrawal bank info update not yet implemented");
    }

    @Override
    public Map<String, Object> getWithdrawalBankInfo(String email) {
        throw new UnsupportedOperationException("Providus withdrawal bank info fetch not yet implemented");
    }

    @Override
    public String initiateWithdrawal(String email, BigDecimal amount, String narration) {
        throw new UnsupportedOperationException("Providus withdrawal not yet implemented");
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(merchantApiKey);
        return headers;
    }

    private String safeString(Object value, String fallback) {
        if (value == null || value.toString().isBlank()) return fallback;
        return value.toString().trim();
    }

    /**
     * Providus expects the international format without the leading '+'.
     * e.g. "08020245369" → "2348020245369"
     */
    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return "";
        String cleaned = phone.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("0")) {
            return "234" + cleaned.substring(1);
        }
        return cleaned;
    }
}
