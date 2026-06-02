package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

        logger.info("Received webhook from SecureWave");

        if (signature == null || signature.isEmpty()) {
            logger.warn("Missing X-Signature in webhook request");
            return ResponseEntity.badRequest().body("Missing signature");
        }

        try {
            webhookService.processSecureWaveWebhook(signature, rawPayload);
            return ResponseEntity.ok("Webhook received");
        } catch (SecurityException e) {
            logger.warn("Rejected SecureWave webhook: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        } catch (Exception e) {
            logger.error("Webhook processing failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Webhook processing failed");
        }
    }

    /**
     * Providus webhook receiver.
     *
     * <p>Providus has not yet documented which header they use for the signature so
     * this endpoint accepts several common candidates
     * ({@code X-Providus-Signature}, {@code X-Signature}, {@code X-Webhook-Signature}).
     * The first non-blank value found is forwarded to the gateway for validation.
     *
     * <p>The endpoint always returns {@code 200 OK} as long as the payload is valid
     * JSON so Providus does not retry already-processed events.  Signature failures
     * return {@code 401} so Providus knows the request was received but rejected.
     *
     * <p><b>Give Providus this URL:</b>
     * {@code https://your-render-domain.onrender.com/api/webhooks/providus}
     */
    @PostMapping("/providus")
    public ResponseEntity<String> handleProvidusWebhook(
            @RequestHeader(value = "X-Providus-Signature", required = false) String providusSig,
            @RequestHeader(value = "X-Signature",          required = false) String xSig,
            @RequestHeader(value = "X-Webhook-Signature",  required = false) String webhookSig,
            @RequestBody String rawPayload) {

        logger.info("[PROVIDUS-WEBHOOK] Received webhook call");

        // Accept whichever signature header Providus actually sends
        String signature = firstPresent(providusSig, xSig, webhookSig);
        if (signature == null) {
            // No signature at all — forward anyway; the gateway will decide
            // (catch-all mode until Providus confirms their header name)
            logger.warn("[PROVIDUS-WEBHOOK] No signature header present — forwarding to gateway in catch-all mode");
        }

        try {
            webhookService.processProvidusWebhook(signature, rawPayload);
            return ResponseEntity.ok("Webhook received");
        } catch (SecurityException e) {
            logger.warn("[PROVIDUS-WEBHOOK] Rejected — {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Processing failed", e);
            // Return 200 so Providus does not retry; the raw payload is already persisted
            // in webhook_events with status=FAILED for manual review
            return ResponseEntity.ok("Webhook received (processing error logged)");
        }
    }

    /**
     * Rubies MFB webhook receiver.
     *
     * <p>Rubies signs the payload with HMAC-SHA512 and sends the signature in the
     * {@code X-Rubies-Signature} header. This endpoint accepts that header (and a
     * generic {@code X-Signature} fallback) and forwards to the service layer.
     *
     * <p><b>Give Rubies this URL:</b>
     * {@code https://your-render-domain.onrender.com/api/webhooks/rubies}
     */
    @PostMapping("/rubies")
    public ResponseEntity<String> handleRubiesWebhook(
            @RequestHeader(value = "X-Rubies-Signature", required = false) String rubiesSig,
            @RequestHeader(value = "X-Signature",        required = false) String xSig,
            @RequestBody String rawPayload) {

        logger.info("[RUBIES-WEBHOOK] Received webhook call");

        String signature = firstPresent(rubiesSig, xSig);

        try {
            webhookService.processRubiesWebhook(signature, rawPayload);
            return ResponseEntity.ok("Webhook received");
        } catch (SecurityException e) {
            logger.warn("[RUBIES-WEBHOOK] Rejected — {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        } catch (Exception e) {
            logger.error("[RUBIES-WEBHOOK] Processing failed", e);
            // Return 200 so Rubies does not retry; raw payload is persisted in webhook_events
            return ResponseEntity.ok("Webhook received (processing error logged)");
        }
    }

    /** Returns the first non-blank value from the given candidates, or {@code null}. */
    private String firstPresent(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }
}
