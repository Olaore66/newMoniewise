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
    private final WebhookReplayProtectionService replayProtectionService;

    @Value("${securewave.secret-key}")
    private String secureWaveSecretKey;

    public WebhookService(WalletService walletService, WebhookReplayProtectionService replayProtectionService) {
        this.walletService = walletService;
        this.replayProtectionService = replayProtectionService;
    }

    public void processSecureWaveWebhook(String signatureHeader, String rawPayload) {
        if (secureWaveSecretKey == null || secureWaveSecretKey.isBlank()) {
            throw new IllegalStateException("SecureWave webhook secret is not configured");
        }
        String cleanPayload = normalizePayload(rawPayload);
        String calculatedHash = calculateHmacSha256(cleanPayload, secureWaveSecretKey);
        if (!calculatedHash.equalsIgnoreCase(signatureHeader)) {
            logger.warn("Rejected SecureWave webhook due to signature mismatch");
            throw new SecurityException("Invalid SecureWave webhook signature");
        }
        if (!replayProtectionService.registerIfNew("securewave", cleanPayload)) {
            throw new SecurityException("Duplicate SecureWave webhook event rejected");
        }
        try {
            JsonNode root = objectMapper.readTree(cleanPayload);
            String notificationStatus = root.path("notification_status").asText();
            if ("payment_successful".equalsIgnoreCase(notificationStatus)) {
                String email = root.path("customer").path("email").asText();
                BigDecimal grossAmount = new BigDecimal(root.path("amount").asText());
                BigDecimal fee = new BigDecimal(root.path("fees").asText());
                BigDecimal netAmount = new BigDecimal(root.path("settlement_amount").asText());
                String transactionId = root.path("transaction_id").asText();
                String description = String.format("Deposit of ₦%s (minus ₦%s processing fee)", grossAmount, fee);
                logger.info("Funding wallet for {} with net amount {}", email, netAmount);
                walletService.processSuccessfulFunding(email, netAmount, grossAmount, fee, transactionId, description, LocalDateTime.now());
            } else {
                logger.info("Ignoring SecureWave webhook with notification status {}", notificationStatus);
            }
        } catch (Exception e) {
            logger.error("SecureWave webhook processing failed", e);
            throw new RuntimeException("Webhook processing failed", e);
        }
    }

    private String normalizePayload(String rawPayload) {
        String cleanPayload = rawPayload == null ? "" : rawPayload.trim();
        if (cleanPayload.startsWith("\"") && cleanPayload.endsWith("\"")) {
            cleanPayload = cleanPayload.substring(1, cleanPayload.length() - 1);
        }
        return cleanPayload.replace("\\\"", "\"");
    }

    private String calculateHmacSha256(String payload, String secret) {
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] hash = sha256Hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error calculating HMAC for webhook", e);
        }
    }
}