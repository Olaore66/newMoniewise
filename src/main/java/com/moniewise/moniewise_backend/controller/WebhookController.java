package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/securewave")
    public ResponseEntity<String> handleSecureWaveWebhook(
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawPayload) {

        logger.info("🔔 Received Webhook from SecureWave");

        if (signature == null || signature.isEmpty()) {
            logger.warn("🚨 Missing X-Signature in webhook request!");
            return ResponseEntity.badRequest().body("Missing signature");
        }

        try {
            webhookService.processSecureWaveWebhook(signature, rawPayload);
        } catch (Exception e) {
            logger.error("❌ Webhook processing failed: {}", e.getMessage());
        }

        // Always return 200 OK quickly so the provider doesn't retry
        return ResponseEntity.ok("Webhook received");
    }
}