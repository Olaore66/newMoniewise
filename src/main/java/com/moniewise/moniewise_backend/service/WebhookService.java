package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.WebhookEvent;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.PaymentGatewayResolver;
import com.moniewise.moniewise_backend.psp.ProvidusExpressGateway;
import com.moniewise.moniewise_backend.psp.SecureWaveGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventService webhookEventService;
    private final WalletWebhookService walletWebhookService;
    private final PaymentGatewayResolver paymentGatewayResolver;

    public WebhookService(
            WebhookEventService webhookEventService,
            WalletWebhookService walletWebhookService,
            PaymentGatewayResolver paymentGatewayResolver
    ) {
        this.webhookEventService = webhookEventService;
        this.walletWebhookService = walletWebhookService;
        this.paymentGatewayResolver = paymentGatewayResolver;
    }

    public void processSecureWaveWebhook(String signatureHeader, String rawPayload) {
        PaymentGateway gateway = paymentGatewayResolver.resolveByProviderName(SecureWaveGateway.PROVIDER_NAME);
        String cleanPayload = normalizePayload(rawPayload);

        if (!gateway.validateWebhookSignature(signatureHeader, cleanPayload)) {
            logger.warn("Rejected SecureWave webhook due to signature mismatch");
            throw new SecurityException("Invalid SecureWave webhook signature");
        }

        String providerName = gateway.getProviderName();
        String eventType = gateway.extractWebhookEventType(cleanPayload);
        String externalReference = gateway.extractWebhookReference(cleanPayload);
        String idempotencyKey = generateIdempotencyKey(providerName, externalReference, cleanPayload);

        if (webhookEventService.alreadyProcessed(providerName, idempotencyKey)) {
            logger.info("Duplicate SecureWave webhook detected, skipping processing");
            return;
        }

        WebhookEvent event = webhookEventService.saveIfNew(
                providerName,
                eventType,
                externalReference,
                idempotencyKey,
                signatureHeader,
                cleanPayload,
                null
        );

        try {
            if (isFundingEvent(eventType)) {
                walletWebhookService.processFundingWebhook(cleanPayload);
            } else {
                logger.info("Ignoring unsupported SecureWave webhook event type {}", eventType);
            }
            webhookEventService.markProcessed(event.getId());
        } catch (Exception e) {
            logger.error("Webhook processing failed for event {}", event.getId(), e);
            webhookEventService.markFailed(event.getId(), e.getMessage());
            throw e;
        }
    }

    private String normalizePayload(String rawPayload) {
        String cleanPayload = rawPayload == null ? "" : rawPayload.trim();
        if (cleanPayload.startsWith("\"") && cleanPayload.endsWith("\"")) {
            cleanPayload = cleanPayload.substring(1, cleanPayload.length() - 1);
        }
        return cleanPayload.replace("\\\"", "\"");
    }

    private String generateIdempotencyKey(String providerName, String externalReference, String payload) {
        if (externalReference != null && !externalReference.isBlank()) {
            return externalReference;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = providerName + payload;
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
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
            throw new RuntimeException("Error generating idempotency key", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Providus webhook processing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for all Providus webhook events.
     *
     * <p>Flow:
     * <ol>
     *   <li>Log the raw payload immediately (critical for learning Providus's format)</li>
     *   <li>Validate signature (HMAC-SHA256 if secret configured, catch-all mode otherwise)</li>
     *   <li>Extract event type and reference, generate idempotency key</li>
     *   <li>Persist to {@code webhook_events} for auditability</li>
     *   <li>Route to deposit processor, withdrawal-success, or withdrawal-failed handler</li>
     * </ol>
     *
     * @param signatureHeader value of whichever signature header Providus sends
     *                        (accepted from multiple header names in the controller)
     * @param rawPayload      the raw request body exactly as Providus sent it
     */
    public void processProvidusWebhook(String signatureHeader, String rawPayload) {
        String cleanPayload = normalizePayload(rawPayload);

        // ── Log FIRST — before any processing so we capture the real Providus format ──
        logger.info("[PROVIDUS-WEBHOOK] ===== Incoming payload =====\n{}", cleanPayload);

        PaymentGateway gateway = paymentGatewayResolver.resolveByProviderName(ProvidusExpressGateway.PROVIDER_NAME);

        if (!gateway.validateWebhookSignature(signatureHeader, cleanPayload)) {
            logger.warn("[PROVIDUS-WEBHOOK] Rejected — invalid signature");
            throw new SecurityException("Invalid Providus webhook signature");
        }

        String providerName  = gateway.getProviderName();
        String eventType     = gateway.extractWebhookEventType(cleanPayload);
        String externalRef   = gateway.extractWebhookReference(cleanPayload);
        String idempotencyKey = generateIdempotencyKey(providerName, externalRef, cleanPayload);

        logger.info("[PROVIDUS-WEBHOOK] eventType={} reference={} idempotencyKey={}",
                eventType, externalRef, idempotencyKey);

        if (webhookEventService.alreadyProcessed(providerName, idempotencyKey)) {
            logger.info("[PROVIDUS-WEBHOOK] Duplicate detected (key={}) — skipping", idempotencyKey);
            return;
        }

        String headersJson = signatureHeader != null
                ? "{\"X-Signature\":\"" + signatureHeader + "\"}"
                : null;

        WebhookEvent event = webhookEventService.saveIfNew(
                providerName, eventType, externalRef,
                idempotencyKey, signatureHeader, cleanPayload, headersJson);

        try {
            if (isProvidusDepositEvent(eventType)) {
                walletWebhookService.processProvidusDepositWebhook(cleanPayload);

            } else if (isProvidusTransferSuccessEvent(eventType)) {
                walletWebhookService.processProvidusWithdrawalWebhook(cleanPayload, true);

            } else if (isProvidusTransferFailedEvent(eventType)) {
                walletWebhookService.processProvidusWithdrawalWebhook(cleanPayload, false);

            } else {
                // Unknown/unhandled event type — record it but don't crash.
                // Once the first live webhook arrives we can add handling here.
                logger.info("[PROVIDUS-WEBHOOK] Unhandled event type '{}' — stored for review", eventType);
            }

            webhookEventService.markProcessed(event.getId());

        } catch (Exception e) {
            logger.error("[PROVIDUS-WEBHOOK] Processing failed for WebhookEvent id={}", event.getId(), e);
            webhookEventService.markFailed(event.getId(), e.getMessage());
            throw e;
        }
    }

    // ── Providus event-type classifiers ──────────────────────────────────────

    private boolean isProvidusDepositEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return u.contains("DEPOSIT")   || u.contains("CREDIT")
            || u.contains("FUND")      || u.contains("SUCCESSFUL_TRANSACTION")
            || u.contains("PAYMENT_SUCCESSFUL") || u.contains("TRANSACTION_SUCCESSFUL")
            || u.contains("INCOMING")  || u.contains("WALLET_CREDITED")
            || u.contains("WALLET_FUNDED");
    }

    private boolean isProvidusTransferSuccessEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return (u.contains("TRANSFER") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")))
            || (u.contains("WITHDRAWAL") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")))
            || (u.contains("DEBIT") && (u.contains("SUCCESS") || u.contains("SUCCESSFUL")));
    }

    private boolean isProvidusTransferFailedEvent(String eventType) {
        if (eventType == null) return false;
        String u = eventType.toUpperCase();
        return (u.contains("TRANSFER") && (u.contains("FAIL") || u.contains("FAILED")))
            || (u.contains("WITHDRAWAL") && (u.contains("FAIL") || u.contains("FAILED")))
            || (u.contains("DEBIT") && (u.contains("FAIL") || u.contains("FAILED")));
    }

    private boolean isFundingEvent(String eventType) {
        return "SUCCESSFUL_TRANSACTION".equalsIgnoreCase(eventType)
                || "payment_successful".equalsIgnoreCase(eventType);
    }
}
