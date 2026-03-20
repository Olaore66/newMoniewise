package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@Service
@Primary // 👈 This tells Spring to use SecureWave instead of any other provider
@Slf4j
@RequiredArgsConstructor
public class SecureWavePaymentProvider implements PaymentProvider {

    private final RestTemplate restTemplate;

    @Value("${securewave.base-url}")
    private String baseUrl;

    @Value("${securewave.secret-key}")
    private String secretKey;

    @Value("${securewave.public-key}")
    private String publicKey;

    @Value("${securewave.business-id}")
    private String businessId;

    // --- Helper: SecureWave Headers ---
    private HttpHeaders getSecureWaveHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + secretKey);
        headers.set("x-api-key", publicKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        return headers;
    }

    // ==========================================================
    // 1. GENERATE VIRTUAL ACCOUNT (The Onboarding Step)
    // ==========================================================
    @Override
    public Map<String, String> createVirtualAccount(User user) {
        String url = baseUrl + "/virtual_accounts/generate";

        // 1. Safely extract data from the User entity
        Map<String, Object> profile = user.getProfileData() != null ? user.getProfileData() : new HashMap<>();
        String firstName = profile.getOrDefault("firstName", "Wisemonie").toString();
        String lastName = profile.getOrDefault("lastName", "User").toString();
        
        // Ensure BVN exists before calling!
        if (user.getBvn() == null || user.getBvn().isEmpty()) {
            log.error("Cannot generate SecureWave account: Missing BVN for user {}", user.getEmail());
            throw new IllegalStateException("BVN is required to generate a virtual account.");
        }

        // 2. Build the exact payload SecureWave requested
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", user.getEmail());
        payload.put("first_name", firstName);
        payload.put("last_name", lastName);
        payload.put("phone_number", user.getPhone());
        payload.put("bank_code", Arrays.asList(1, 2, 3)); // As per their docs
        payload.put("business_id", businessId);
        payload.put("account_type", "static");
        payload.put("id_type", "bvn");
        payload.put("id_number", user.getBvn());

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                Map<String, Object> body = response.getBody();
                
                // ⚠️ NOTE: You must verify these exact keys ("account_number", "bank_name") match SecureWave's actual JSON response!
                Map<String, Object> data = (Map<String, Object>) body.get("data"); // Assuming data is wrapped in a "data" object
                
                Map<String, String> result = new HashMap<>();
                result.put("accountNumber", data.get("account_number").toString());
                result.put("bank", data.get("bank_name").toString());
                return result;
            }
        } catch (Exception e) {
            log.error("SecureWave Virtual Account Generation Failed: {}", e.getMessage());
            throw new RuntimeException("Failed to generate virtual account");
        }
        return new HashMap<>();
    }

    // ==========================================================
    // 2. RESOLVE ACCOUNT (The KYC Verification Step)
    // ==========================================================
    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        // Use the exact endpoint provided by SecureWave
        String url = baseUrl + "/customer_withdrawals/validate-account-name";

        Map<String, Object> payload = new HashMap<>();
        payload.put("bank_code", bankCode);
        payload.put("account_number", accountNumber);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();

                // 1. Check if the provider successfully found the account
                Boolean status = (Boolean) body.get("status");

                if (status != null && status) {
                    // 2. Extract the nested "data" object
                    Map<String, Object> data = (Map<String, Object>) body.get("data");

                    if (data != null && data.containsKey("account_name")) {
                        // 3. Return the verified name! (e.g., "JOHN DOE")
                        return data.get("account_name").toString();
                    }
                } else {
                    // If status is false, log the message they sent back
                    log.warn("Account validation failed: {}", body.get("message"));
                }
            }
        } catch (Exception e) {
            log.error("SecureWave Account Resolution Failed: {}", e.getMessage());
        }

        // If we reach here, the account doesn't exist or the bank network is down
        throw new RuntimeException("Could not verify account details. Please check your account number and bank.");
    }

    // ==========================================================
    // 3. WITHDRAWAL (The Closed-Loop Payout Step)
    // ==========================================================
    @Override
    public String initiateTransfer(String bankCode, String accountNumber, String accountName, BigDecimal amount, String reference, String narration) {
        // Replace with SecureWave's actual disbursement endpoint
        String url = baseUrl + "/transfers/initiate";

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("reference", reference);
        payload.put("narration", narration);
        payload.put("bank_code", bankCode);
        payload.put("account_number", accountNumber);
        payload.put("currency", "NGN");

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                Map<String, Object> body = response.getBody();
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                
                return data.get("transaction_reference").toString(); 
            }
        } catch (Exception e) {
            log.error("SecureWave Transfer Failed: {}", e.getMessage());
            throw new RuntimeException("Transfer Failed: " + e.getMessage());
        }
        throw new RuntimeException("Transfer Failed");
    }

    // ==========================================================
    // 4. CARD PAYMENT (Optional / If Supported)
    // ==========================================================
    public Map<String, String> initializeCardPayment(User user, BigDecimal amount) {
        // If SecureWave supports card checkouts, put their initialization URL here.
        // Otherwise, throw an UnsupportedOperationException if you are strictly using Virtual Accounts for funding now.
        throw new UnsupportedOperationException("Card payments are not configured for SecureWave yet.");
    }

    // Add this near the top of your class to hold the cached banks
    private List<Map<String, Object>> cachedBanks = null;

    // ==========================================================
    // 5. GET BANKS (With In-Memory Caching)
    // ==========================================================
    @Override
    public List<Map<String, Object>> getSupportedBanks() {
        // Return cached version instantly if we already downloaded it
        if (cachedBanks != null && !cachedBanks.isEmpty()) {
            return cachedBanks;
        }

        String url = baseUrl + "/banks"; // Notice this matches the /api/banks path

        try {
            // Use exchange() instead of postForEntity() because this is a GET request
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();

                // Verify the "status": true flag from their JSON
                Boolean status = (Boolean) body.get("status");
                if (status != null && status) {
                    cachedBanks = (List<Map<String, Object>>) body.get("data");
                    return cachedBanks;
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch banks from SecureWave: {}", e.getMessage());
        }

        return Collections.emptyList(); // Return empty if it fails so the app doesn't crash
    }

    // ==========================================================
    // 6. UPDATE WITHDRAWAL BANK INFO
    // ==========================================================
    @Override
    public boolean updateWithdrawalBankInfo(String email, String bankName, String accountName, String bankCode, String accountNumber) {
        String url = baseUrl + "/customer_withdrawals/bank-info";

        Map<String, Object> payload = new HashMap<>();
        payload.put("customer_email", email);
        payload.put("bank_name", bankName);
        payload.put("account_name", accountName);
        payload.put("bank_code", bankCode);
        payload.put("account_number", accountNumber);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // Return true if status is true in their JSON response
                Boolean status = (Boolean) response.getBody().get("status");
                return status != null && status;
            }
        } catch (Exception e) {
            log.error("SecureWave Update Bank Info Failed: {}", e.getMessage());
        }

        throw new RuntimeException("Failed to update bank details with the payment provider.");
    }

    // ==========================================================
    // 7. INITIATE WITHDRAWAL (Closed-Loop)
    // ==========================================================
    @Override
    public String initiateWithdrawal(String email, BigDecimal amount, String narration) {
        String url = baseUrl + "/customer_withdrawals/withdraw";

        Map<String, Object> payload = new HashMap<>();
        payload.put("customer_email", email);
        payload.put("amount", amount);
        payload.put("narration", narration);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();

                Boolean outerStatus = (Boolean) body.get("status");
                if (outerStatus != null && outerStatus) {

                    // Navigate through the double "data" nesting
                    Map<String, Object> outerData = (Map<String, Object>) body.get("data");
                    if (outerData != null) {
                        Map<String, Object> innerData = (Map<String, Object>) outerData.get("data");

                        if (innerData != null && innerData.containsKey("reference")) {
                            return innerData.get("reference").toString(); // The SecureWave Reference!
                        }
                    }
                } else {
                    log.error("SecureWave Withdrawal Failed: {}", body.get("message"));
                    throw new RuntimeException(body.get("message").toString());
                }
            }
        } catch (Exception e) {
            log.error("SecureWave Withdrawal API Exception: {}", e.getMessage());
            throw new RuntimeException("Failed to process withdrawal with the payment provider.");
        }

        throw new RuntimeException("Withdrawal processing failed.");
    }
}