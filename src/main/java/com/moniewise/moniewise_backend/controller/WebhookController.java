package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    private final WalletService walletService;
    private final String paystackSecretKey;

    public WebhookController(WalletService walletService, @Value("${paystack.secret.key}") String paystackSecretKey) {
        this.walletService = walletService;
        this.paystackSecretKey = paystackSecretKey;
    }

    @PostMapping("/paystack")
    public ResponseEntity<?> handlePaystackWebhook(
            @RequestHeader("x-paystack-signature") String signature,
            @RequestBody Map<String, Object> payload
    ) {
        try {
            // Verify signature
            String computedSignature = computePaystackSignature(payload.toString());
            if (!computedSignature.equals(signature)) {
                logger.warn("Invalid Paystack signature");
                return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
            }

            String event = (String) payload.get("event");
            if ("charge.success".equals(event)) {
                Map<String, Object> data = (Map<String, Object>) payload.get("data");
                BigDecimal amount = new BigDecimal(data.get("amount").toString()).divide(new BigDecimal("100"), 2, BigDecimal.ROUND_HALF_UP);
                Long userId = Long.parseLong((String) data.get("metadata.userId"));
                walletService.fundWallet(userId, amount);
                logger.info("Processed Paystack charge.success for user {}: ₦{}", userId, amount);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Webhook error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String computePaystackSignature(String payload) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA512");
        SecretKeySpec secretKeySpec = new SecretKeySpec(paystackSecretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}