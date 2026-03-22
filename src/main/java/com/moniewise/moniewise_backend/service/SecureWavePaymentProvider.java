package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
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
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Bearer " + secretKey); // Ensure "Bearer " has a space!
        headers.set("x-api-key", publicKey);
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

                Object dataObj = body.get("data");
                Map<String, Object> accountData = new HashMap<>();

                // Handle the Array that SecureWave sends back
                if (dataObj instanceof List) {
                    List<?> dataList = (List<?>) dataObj;
                    if (!dataList.isEmpty()) {
                        accountData = (Map<String, Object>) dataList.get(0);
                    }
                } else if (dataObj instanceof Map) {
                    accountData = (Map<String, Object>) dataObj;
                }

                // 🚨 Use the exact keys from the SecureWave docs!
                Object accNumObj = accountData.get("account_number");
                Object bankObj = accountData.get("account_bank"); // <--- THE MISSING LINK!

                if (accNumObj == null || bankObj == null) {
                    log.error("SecureWave Response missing keys: {}", accountData);
                    throw new RuntimeException("SecureWave did not return valid account details.");
                }

                Map<String, String> result = new HashMap<>();
                result.put("accountNumber", String.valueOf(accNumObj));
                result.put("bank", String.valueOf(bankObj));

                return result;
            }
        } catch (Exception e) {
            log.error("SecureWave Virtual Account Generation Failed: {}", e.getMessage());
            throw new RuntimeException("Failed to generate virtual account");
        }
        return new HashMap<>();
    }

    // ==========================================================
    // 2. RESOLVE ACCOUNT (The KYC Verification Step - X-RAY EDITION)
    // ==========================================================
    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        String url = baseUrl + "/customer_withdrawals/validate-account-name";

        try {
            // 1. Build the exact JSON String
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> payloadMap = new HashMap<>();
            payloadMap.put("bank_code", bankCode);
            payloadMap.put("account_number", accountNumber);
            String jsonBody = mapper.writeValueAsString(payloadMap);

            // 2. Get the headers
            HttpHeaders headers = getSecureWaveHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 🕵️‍♂️ THE X-RAY: Print EXACTLY what we are sending
            // We mask the middle of the keys so you don't leak them in logs, but we show the start/end to catch spaces!
            String safeSecret = secretKey != null && secretKey.length() > 8
                    ? secretKey.substring(0, 6) + "..." + secretKey.substring(secretKey.length() - 2) : "INVALID_KEY";
            String authHeaderValue = headers.getFirst("Authorization");

            log.info("================ SECUREWAVE OUTGOING X-RAY ================");
            log.info("URL:         --> '{}'", url);
            log.info("Raw Secret:  --> '{}'", safeSecret);
            log.info("Auth Header: --> '{}'", authHeaderValue);
            log.info("Payload:     --> {}", jsonBody);
            log.info("===========================================================");

            // 3. Send the pristine JSON String
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(jsonBody, headers),
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                Boolean status = (Boolean) body.get("status");

                if (status != null && status) {
                    Map<String, Object> data = (Map<String, Object>) body.get("data");
                    if (data != null && data.containsKey("account_name")) {
                        return (String) data.get("account_name");
                    }
                } else {
                    log.error("SecureWave returned false status: {}", body.get("message"));
                }
            }
            throw new RuntimeException("Could not verify account name. Please check the details.");

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 🚨 CAPTURE THE EXACT ERROR FROM THE PROVIDER
            log.error("❌ SecureWave REJECTED the request.");
            log.error("HTTP Status: {}", e.getStatusCode());
            log.error("Response Body: {}", e.getResponseBodyAsString());

            // Pass the actual message back up so you can see it in Postman
            throw new RuntimeException("SecureWave API Error: " + e.getResponseBodyAsString());

        } catch (Exception e) {
            log.error("SecureWave Account Resolution Failed: {}", e.getMessage(), e);
            throw new RuntimeException("An internal error occurred during verification.");
        }
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

    // Add this near the top of your class to hold the cached banks
    private List<Map<String, Object>> cachedBanks = null;

    // ==========================================================
    // 5. GET BANKS (With In-Memory Caching)
    // ==========================================================
    @Override
    @Cacheable(value = "banks")
    public List<Map<String, Object>> getSupportedBanks() {
        // Using the base URL from your environment variables
        String url = baseUrl + "/banks";

        try {
            // We expect a Map representing the outer JSON object { "status": true, "data": [...] }
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(getSecureWaveHeaders()),
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();

                // 1. Check if the API says it was successful
                Boolean status = (Boolean) body.get("status");
                if (status != null && status) {

                    // 2. Safely extract the "data" array
                    Object dataObj = body.get("data");
                    if (dataObj instanceof List) {
                        return (List<Map<String, Object>>) dataObj;
                    }
                } else {
                    System.out.println("SecureWave returned failure status: {} " +  body.get("message"));
                }
            }
        } catch (Exception e) {
            System.out.println("SecureWave API Exception while fetching banks: {} " + e.getMessage());
        }

        // If anything fails, return an empty list.
        // Your controller will catch this and gracefully tell the user "Bank list is currently unavailable"
        return new ArrayList<>();
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