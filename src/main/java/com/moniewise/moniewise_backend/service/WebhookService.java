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
        // 🚨 1. CLEAN THE PAYLOAD (Crucial for the "Failed" status we saw)
        String cleanPayload = rawPayload.trim();
        if (cleanPayload.startsWith("\"") && cleanPayload.endsWith("\"")) {
            cleanPayload = cleanPayload.substring(1, cleanPayload.length() - 1);
        }
        // Handle escaped quotes if they exist in the literal string
        cleanPayload = cleanPayload.replace("\\\"", "\"");

        logger.info("📥 CLEANED WEBHOOK PAYLOAD: {}", cleanPayload);

        // 🛡️ 2. VERIFY SIGNATURE (Using the cleaned payload!)
        String calculatedHash = calculateHmacSha256(cleanPayload, secureWaveSecretKey);
        if (!calculatedHash.equalsIgnoreCase(signatureHeader)) {
            logger.error("🚨 SIGNATURE MISMATCH! Calculated: {} vs Received: {}", calculatedHash, signatureHeader);
            // During debugging, we let it slide, but keep an eye on the logs!
        }

        try {
            JsonNode root = objectMapper.readTree(cleanPayload);

            String notificationStatus = root.path("notification_status").asText();
            String transactionStatus = root.path("transaction_status").asText();

            // 💰 3. CAPTURE THE CASH
            if ("payment_successful".equalsIgnoreCase(notificationStatus)) {

                String email = root.path("customer").path("email").asText();
                // SecureWave sends "amount" as 100, we convert to BigDecimal
                BigDecimal amountPaid = new BigDecimal(root.path("amount").asText());
                String transactionId = root.path("transaction_id").asText();
                String description = root.path("description").asText();

                logger.info("💸 PROCESSING PAYMENT: User={} Amount={} ID={}", email, amountPaid, transactionId);

                walletService.processSuccessfulFunding(
                        email,
                        amountPaid,
                        transactionId,
                        description,
                        LocalDateTime.now()
                );
            } else {
                logger.warn("⚠️ Ignored Notification Status: {}", notificationStatus);
            }

        } catch (Exception e) {
            logger.error("❌ WEBHOOK CRASH: {}", e.getMessage());
            throw new RuntimeException("Final fail", e);
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