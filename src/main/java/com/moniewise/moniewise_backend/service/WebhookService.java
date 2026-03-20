package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WalletService walletService;

    @Value("${securewave.secret-key}")
    private String secureWaveSecretKey;

    public WebhookService(WalletService walletService) {
        this.walletService = walletService;
    }

    public void processSecureWaveWebhook(String signatureHeader, String rawPayload) {
        // 1. 🛡️ VERIFY THE SIGNATURE (HMAC-SHA256 as per docs)
        String calculatedHash = calculateHmacSha256(rawPayload, secureWaveSecretKey);

        if (!calculatedHash.equalsIgnoreCase(signatureHeader)) {
            logger.error("🚨 CRITICAL: Webhook signature mismatch! Possible spoofing attack.");
            throw new SecurityException("Invalid webhook signature");
        }

        // 2. 🏗️ PARSE THE EXACT JSON STRUCTURE FROM DOCS
        try {
            JsonNode root = objectMapper.readTree(rawPayload);

            String notificationStatus = root.path("notification_status").asText();
            String transactionStatus = root.path("transaction_status").asText();

            // Verify it is a successful funding event
            if ("payment_successful".equals(notificationStatus) && "success".equals(transactionStatus)) {

                String email = root.path("customer").path("email").asText();
                BigDecimal amountPaid = root.path("amount").decimalValue();
                String transactionId = root.path("transaction_id").asText();
                String description = root.path("description").asText();

                // Safety check
                if (email == null || email.isEmpty() || amountPaid == null) {
                    logger.error("Webhook payload missing critical data (Email/Amount): {}", rawPayload);
                    return;
                }

                logger.info("💰 Valid Webhook Detected! Funding Wallet: User={} Amount={}", email, amountPaid);

                // 3. ⚡ ATOMIC DB UPDATE
                walletService.processSuccessfulFunding(
                        email,
                        amountPaid,
                        transactionId,
                        description,
                        LocalDateTime.now()
                );

            } else {
                logger.info("Ignoring webhook event. Notification: {}, Status: {}", notificationStatus, transactionStatus);
            }

        } catch (Exception e) {
            logger.error("Failed to parse or process webhook payload", e);
            throw new RuntimeException("Webhook processing error", e);
        }
    }

    /**
     * Helper method to generate HMAC SHA256 hash using standard Java libraries
     */
    private String calculateHmacSha256(String payload, String secret) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);

            byte[] hash = sha256_HMAC.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error calculating HMAC for webhook", e);
        }
    }
}